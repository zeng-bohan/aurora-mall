package com.zengbohan.aurora.order.service;

import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.order.dto.CouponTemplateView;
import com.zengbohan.aurora.order.dto.CouponView;
import com.zengbohan.aurora.order.entity.CouponStatus;
import com.zengbohan.aurora.order.entity.CouponTemplate;
import com.zengbohan.aurora.order.entity.UserCoupon;
import com.zengbohan.aurora.order.mapper.CouponTemplateMapper;
import com.zengbohan.aurora.order.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 领券的资格判定、防超发守卫与"一人一张"的三层配合，加上建券校验与状态推导。
 */
class CouponServiceTest {

    private static final long TEMPLATE_ID = 7L;
    private static final long USER_ID = 3L;

    private CouponTemplateMapper templateMapper;
    private UserCouponMapper couponMapper;
    private CouponService service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(CouponTemplateMapper.class);
        couponMapper = mock(UserCouponMapper.class);
        service = new CouponService(templateMapper, couponMapper);
    }

    // ---------- 建券 ----------

    @Test
    void createTemplateInsertsWithZeroClaimed() {
        when(templateMapper.insert(any(CouponTemplate.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CouponTemplate.class).setId(11L);
            return 1;
        });

        assertThat(service.createTemplate(command("满 100 减 20", "100.00", "20.00", 500, 30))).isEqualTo(11L);

        ArgumentCaptor<CouponTemplate> saved = ArgumentCaptor.forClass(CouponTemplate.class);
        verify(templateMapper).insert(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("满 100 减 20");
        assertThat(saved.getValue().getTotal()).isEqualTo(500);
        assertThat(saved.getValue().getClaimed()).as("新模板的已领数必须从 0 起").isZero();
        assertThat(saved.getValue().getValidDays()).isEqualTo(30);
    }

    @Test
    void createTemplateRejectsBadInputWithoutTouchingDb() {
        assertRejected(command(" ", "100.00", "20.00", 10, 30));                       // 标题空
        assertRejected(command("t", "0.00", "20.00", 10, 30));                          // 门槛非正
        assertRejected(command("t", "100.00", "100.00", 10, 30));                       // 抵扣 >= 门槛
        assertRejected(command("t", "100.00", "20.00", 0, 30));                         // 总量非正
        assertRejected(command("t", "100.00", "20.00", 10, 0));                         // 有效期非正
        assertRejected(new CouponService.CreateTemplate("t", new BigDecimal("100.00"),
                new BigDecimal("20.00"), 10, LocalDateTime.now(), LocalDateTime.now().minusHours(1), 30)); // 窗口倒置
        assertRejected(new CouponService.CreateTemplate("t", new BigDecimal("100.00"),
                new BigDecimal("20.00"), 10, LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusDays(1), 30));                                 // 已停发
    }

    // ---------- 领券 ----------

    @Test
    void claimRejectsOutsideClaimWindow() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2)));
        assertClaimCode(ErrorCode.COUPON_NOT_STARTED);

        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().minusDays(2), LocalDateTime.now().minusDays(1)));
        assertClaimCode(ErrorCode.COUPON_CLAIM_ENDED);

        verify(templateMapper, never()).claimOne(anyLong());
    }

    @Test
    void claimRejectsUnknownTemplate() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(TEMPLATE_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(templateMapper, never()).claimOne(anyLong());
    }

    @Test
    void claimRejectsSecondClaimBySameUserBeforeSpendingQuota() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)));
        when(couponMapper.selectCount(any())).thenReturn(1L);

        assertClaimCode(ErrorCode.COUPON_ALREADY_CLAIMED);
        verify(templateMapper, never()).claimOne(anyLong());
    }

    @Test
    void claimRejectsWhenQuotaExhausted() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)));
        when(couponMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.claimOne(TEMPLATE_ID)).thenReturn(0);

        assertClaimCode(ErrorCode.COUPON_SOLD_OUT);
        verify(couponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void claimCopiesTemplateSnapshotAndComputesExpiry() {
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)));
        when(couponMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.claimOne(TEMPLATE_ID)).thenReturn(1);
        when(couponMapper.insert(any(UserCoupon.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, UserCoupon.class).setId(88L);
            return 1;
        });

        assertThat(service.claim(TEMPLATE_ID, USER_ID)).isEqualTo(88L);

        ArgumentCaptor<UserCoupon> saved = ArgumentCaptor.forClass(UserCoupon.class);
        verify(couponMapper).insert(saved.capture());
        UserCoupon coupon = saved.getValue();
        assertThat(coupon.getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(coupon.getUserId()).isEqualTo(USER_ID);
        assertThat(coupon.getStatus()).isEqualTo(CouponStatus.UNUSED.name());
        assertThat(coupon.getTitle()).as("券面信息是领取时的快照").isEqualTo("满 100 减 20");
        assertThat(coupon.getThresholdAmount()).isEqualByComparingTo("100.00");
        assertThat(coupon.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(coupon.getExpireAt())
                .as("有效期 = 领取时刻 + validDays")
                .isCloseTo(coupon.getClaimedAt().plusDays(30), within(3, ChronoUnit.SECONDS));
    }

    @Test
    void claimMapsConcurrentDuplicateKeyToAlreadyClaimed() {
        // 并发下同一用户同时领：唯一键失败 → 抛业务码（异常传播触发回滚，配额随之释放）
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1)));
        when(couponMapper.selectCount(any())).thenReturn(0L);
        when(templateMapper.claimOne(TEMPLATE_ID)).thenReturn(1);
        when(couponMapper.insert(any(UserCoupon.class)))
                .thenThrow(new DuplicateKeyException("uk_user_coupon_once"));

        assertClaimCode(ErrorCode.COUPON_ALREADY_CLAIMED);
    }

    // ---------- 查询 ----------

    @Test
    void myCouponsDerivesExpiredOnlyForUnusedCoupons() {
        UserCoupon expiredUnused = coupon(1L, CouponStatus.UNUSED.name(), LocalDateTime.now().minusHours(1));
        UserCoupon liveUnused = coupon(2L, CouponStatus.UNUSED.name(), LocalDateTime.now().plusDays(1));
        UserCoupon used = coupon(3L, CouponStatus.USED.name(), LocalDateTime.now().minusHours(2));
        when(couponMapper.findByUser(USER_ID)).thenReturn(List.of(expiredUnused, liveUnused, used));

        List<CouponView> views = service.myCoupons(USER_ID);

        assertThat(views).extracting(CouponView::status)
                .containsExactly(CouponStatus.EXPIRED.name(), CouponStatus.UNUSED.name(), CouponStatus.USED.name());
    }

    @Test
    void templateViewExposesRemainingQuotaWithFloorAtZero() {
        CouponTemplate template = template(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
        template.setTotal(100);
        template.setClaimed(40);
        when(templateMapper.selectList(any())).thenReturn(List.of(template));

        assertThat(service.listTemplates()).extracting(CouponTemplateView::remaining).containsExactly(60);

        template.setClaimed(140); // 防御性：claimed 不该超过 total，真出现时余量按 0 展示而不是负数
        assertThat(service.listTemplates()).extracting(CouponTemplateView::remaining).containsExactly(0);
    }

    // ---------- helpers ----------

    private void assertClaimCode(ErrorCode expected) {
        assertThatThrownBy(() -> service.claim(TEMPLATE_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }

    private void assertRejected(CouponService.CreateTemplate command) {
        assertThatThrownBy(() -> service.createTemplate(command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_ERROR);
        verify(templateMapper, never()).insert(any(CouponTemplate.class));
    }

    private static CouponService.CreateTemplate command(String title, String threshold, String discount,
                                                        int total, int validDays) {
        return new CouponService.CreateTemplate(title, new BigDecimal(threshold), new BigDecimal(discount),
                total, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1), validDays);
    }

    private static CouponTemplate template(LocalDateTime start, LocalDateTime end) {
        CouponTemplate template = new CouponTemplate();
        template.setId(TEMPLATE_ID);
        template.setTitle("满 100 减 20");
        template.setThresholdAmount(new BigDecimal("100.00"));
        template.setDiscountAmount(new BigDecimal("20.00"));
        template.setTotal(10);
        template.setClaimed(0);
        template.setClaimStartAt(start);
        template.setClaimEndAt(end);
        template.setValidDays(30);
        return template;
    }

    private static UserCoupon coupon(long id, String status, LocalDateTime expireAt) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(id);
        coupon.setTemplateId(TEMPLATE_ID);
        coupon.setUserId(USER_ID);
        coupon.setStatus(status);
        coupon.setTitle("满 100 减 20");
        coupon.setThresholdAmount(new BigDecimal("100.00"));
        coupon.setDiscountAmount(new BigDecimal("20.00"));
        coupon.setExpireAt(expireAt);
        return coupon;
    }

}
