package com.zengbohan.aurora.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.order.dto.CouponTemplateView;
import com.zengbohan.aurora.order.dto.CouponView;
import com.zengbohan.aurora.order.entity.CouponStatus;
import com.zengbohan.aurora.order.entity.CouponTemplate;
import com.zengbohan.aurora.order.entity.UserCoupon;
import com.zengbohan.aurora.order.mapper.CouponTemplateMapper;
import com.zengbohan.aurora.order.mapper.UserCouponMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券（M5 S4：模板与领取；下单抵扣/退款回券见 S5）。
 * <p>
 * 券并入 order 域而不是独立服务：它的生命周期完全围绕订单（领取 → 锁定 → 核销 → 回退），
 * 独立出去只会引入跨服务事务，换不到任何收益。表在 aurora_order 库，服务是同进程的 order。
 */
@Service
public class CouponService {

    // 与列宽一致：校验前置，坏数据不进库
    private static final int TITLE_MAX_LENGTH = 64;

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper couponMapper;

    public CouponService(CouponTemplateMapper templateMapper, UserCouponMapper couponMapper) {
        this.templateMapper = templateMapper;
        this.couponMapper = couponMapper;
    }

    /** 运营建券。校验与列宽/业务语义对齐，任何调用方都过同一套规则。 */
    public long createTemplate(CreateTemplate command) {
        if (command.title() == null || command.title().isBlank()
                || command.title().length() > TITLE_MAX_LENGTH) {
            fail("券标题必填且不超过 " + TITLE_MAX_LENGTH + " 字");
        }
        if (command.thresholdAmount() == null || command.thresholdAmount().signum() <= 0) {
            fail("门槛金额必须大于 0");
        }
        if (command.discountAmount() == null || command.discountAmount().signum() <= 0) {
            fail("抵扣金额必须大于 0");
        }
        if (command.discountAmount().compareTo(command.thresholdAmount()) >= 0) {
            // 「满 100 减 100」这种券只会把订单打成 0 元，不是有效配置
            fail("抵扣金额必须小于门槛金额");
        }
        if (command.total() <= 0) {
            fail("发放总量必须大于 0");
        }
        if (command.claimStartAt() == null || command.claimEndAt() == null) {
            fail("领取起止时间必填");
        }
        if (!command.claimStartAt().isBefore(command.claimEndAt())) {
            fail("领取开始时间必须早于结束时间");
        }
        if (command.claimEndAt().isBefore(LocalDateTime.now())) {
            fail("领取结束时间不能在过去");
        }
        if (command.validDays() <= 0) {
            fail("有效期天数必须大于 0");
        }

        CouponTemplate template = new CouponTemplate();
        template.setTitle(command.title());
        template.setThresholdAmount(command.thresholdAmount());
        template.setDiscountAmount(command.discountAmount());
        template.setTotal(command.total());
        template.setClaimed(0);
        template.setClaimStartAt(command.claimStartAt());
        template.setClaimEndAt(command.claimEndAt());
        template.setValidDays(command.validDays());
        templateMapper.insert(template);
        return template.getId();
    }

    /**
     * 领券。三步的次序与分工是刻意的：
     * <ol>
     *   <li><b>资格判定</b>（窗口、是否已领）——先给出明确的业务码，不让人等到最后才被拒；</li>
     *   <li><b>条件更新占名额</b>——防超发靠 SQL 守卫（{@code claimed < total}），不靠先查后改；</li>
     *   <li><b>插入用户券</b>——一人一张由唯一键兜底。</li>
     * </ol>
     * ②③ 在同一事务：③ 因唯一键失败时整体回滚，② 占掉的配额随之释放——
     * 不会出现"领取失败却少了一张券"。
     */
    @Transactional
    public long claim(long templateId, long userId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "券模板不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getClaimStartAt())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_STARTED);
        }
        if (now.isAfter(template.getClaimEndAt())) {
            throw new BusinessException(ErrorCode.COUPON_CLAIM_ENDED);
        }
        boolean alreadyClaimed = couponMapper.selectCount(Wrappers.<UserCoupon>lambdaQuery()
                .eq(UserCoupon::getTemplateId, templateId)
                .eq(UserCoupon::getUserId, userId)) > 0;
        if (alreadyClaimed) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_CLAIMED);
        }
        if (templateMapper.claimOne(templateId) != 1) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }

        UserCoupon coupon = new UserCoupon();
        coupon.setTemplateId(templateId);
        coupon.setUserId(userId);
        coupon.setStatus(CouponStatus.UNUSED.name());
        coupon.setTitle(template.getTitle());
        coupon.setThresholdAmount(template.getThresholdAmount());
        coupon.setDiscountAmount(template.getDiscountAmount());
        coupon.setClaimedAt(now);
        coupon.setExpireAt(now.plusDays(template.getValidDays()));
        try {
            couponMapper.insert(coupon);
        } catch (DuplicateKeyException e) {
            // 并发下同一用户同时领：唯一键拦下后抛业务码；异常传播触发回滚，
            // 上面占用的名额一并归还（这正是把两步放进同一事务的原因）
            throw new BusinessException(ErrorCode.COUPON_ALREADY_CLAIMED, "该券已领取");
        }
        return coupon.getId();
    }

    /** 我的券：状态在读取时按 expire_at 推导（EXPIRED 不落库），先过期的排前面。 */
    public List<CouponView> myCoupons(long userId) {
        return couponMapper.findByUser(userId).stream().map(CouponService::toView).toList();
    }

    /** 用户视角的可领模板：只给领取窗口未结束的。 */
    public List<CouponTemplateView> claimableTemplates() {
        List<CouponTemplate> templates = templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery()
                .gt(CouponTemplate::getClaimEndAt, LocalDateTime.now())
                .orderByDesc(CouponTemplate::getId));
        return templates.stream().map(CouponService::toView).toList();
    }

    /** 运营视角的模板列表：不筛窗口，含已停发的。 */
    public List<CouponTemplateView> listTemplates() {
        List<CouponTemplate> templates = templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery()
                .orderByDesc(CouponTemplate::getId));
        return templates.stream().map(CouponService::toView).toList();
    }

    private static CouponTemplateView toView(CouponTemplate template) {
        return new CouponTemplateView(template.getId(), template.getTitle(),
                template.getThresholdAmount(), template.getDiscountAmount(),
                Math.max(template.getTotal() - template.getClaimed(), 0),
                template.getClaimStartAt(), template.getClaimEndAt());
    }

    // 派生状态：UNUSED 且已过期 → EXPIRED。其他状态（LOCKED/USED）不再改写展示值
    private static CouponView toView(UserCoupon coupon) {
        String status = coupon.getStatus();
        LocalDateTime expireAt = coupon.getExpireAt();
        if (CouponStatus.UNUSED.name().equals(status) && expireAt != null
                && expireAt.isBefore(LocalDateTime.now())) {
            status = CouponStatus.EXPIRED.name();
        }
        return new CouponView(coupon.getId(), coupon.getTitle(), coupon.getThresholdAmount(),
                coupon.getDiscountAmount(), status, expireAt, coupon.getOrderId());
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    /** 建券命令：把 7 个入参收成一个（避免长参数列表在服务内部继续扩散）。 */
    public record CreateTemplate(String title, BigDecimal thresholdAmount, BigDecimal discountAmount,
                                 int total, LocalDateTime claimStartAt, LocalDateTime claimEndAt,
                                 int validDays) {
    }
}
