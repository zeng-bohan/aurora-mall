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

@Service
public class CouponService {

    private static final int TITLE_MAX_LENGTH = 64;

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper couponMapper;

    public CouponService(CouponTemplateMapper templateMapper, UserCouponMapper couponMapper) {
        this.templateMapper = templateMapper;
        this.couponMapper = couponMapper;
    }

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
            throw new BusinessException(ErrorCode.COUPON_ALREADY_CLAIMED, "该券已领取");
        }
        return coupon.getId();
    }

    public List<CouponView> myCoupons(long userId) {
        return couponMapper.findByUser(userId).stream().map(CouponService::toView).toList();
    }

    public List<CouponTemplateView> claimableTemplates() {
        List<CouponTemplate> templates = templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery()
                .gt(CouponTemplate::getClaimEndAt, LocalDateTime.now())
                .orderByDesc(CouponTemplate::getId));
        return templates.stream().map(CouponService::toView).toList();
    }

    public List<CouponTemplateView> listTemplates() {
        List<CouponTemplate> templates = templateMapper.selectList(Wrappers.<CouponTemplate>lambdaQuery()
                .orderByDesc(CouponTemplate::getId));
        return templates.stream().map(CouponService::toView).toList();
    }

    public CouponUse prepareUse(long couponId, long userId) {
        UserCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getUserId() == null || coupon.getUserId() != userId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        if (!CouponStatus.UNUSED.name().equals(coupon.getStatus())) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
        }
        if (coupon.getExpireAt() != null && coupon.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED);
        }
        return new CouponUse(coupon.getId(), coupon.getTemplateId(), userId,
                coupon.getTitle(), coupon.getThresholdAmount(), coupon.getDiscountAmount());
    }

    public BigDecimal discountFor(CouponUse coupon, BigDecimal merchandiseAmount) {
        if (merchandiseAmount.compareTo(coupon.thresholdAmount()) < 0) {
            throw new BusinessException(ErrorCode.COUPON_THRESHOLD_NOT_MET);
        }
        return coupon.discountAmount().min(merchandiseAmount);
    }

    /** 在订单本地事务内锁定券并把 trade_id 绑上。 */
    public void bindInTransaction(long tradeId, CouponUse coupon) {
        if (couponMapper.lock(coupon.id(), coupon.userId()) != 1) {
            throw new BusinessException(ErrorCode.COUPON_NOT_USABLE);
        }
        couponMapper.bindToTrade(coupon.id(), tradeId);
    }

    public boolean markUsed(long tradeId) {
        return couponMapper.markUsedByTrade(tradeId) == 1;
    }

    public boolean release(long tradeId) {
        return couponMapper.releaseByTrade(tradeId) == 1;
    }

    /** 整单退款：券回到用户券包，已核销的也恢复。 */
    public boolean refundReturn(long tradeId) {
        return couponMapper.refundByTrade(tradeId) == 1;
    }

    public String titleOf(Long userCouponId) {
        if (userCouponId == null) {
            return null;
        }
        UserCoupon coupon = couponMapper.selectById(userCouponId);
        return coupon == null ? null : coupon.getTitle();
    }

    public BigDecimal thresholdOf(Long userCouponId) {
        if (userCouponId == null) {
            return null;
        }
        UserCoupon coupon = couponMapper.selectById(userCouponId);
        return coupon == null ? null : coupon.getThresholdAmount();
    }

    private static CouponTemplateView toView(CouponTemplate template) {
        return new CouponTemplateView(template.getId(), template.getTitle(),
                template.getThresholdAmount(), template.getDiscountAmount(),
                Math.max(template.getTotal() - template.getClaimed(), 0), template.getTotal(),
                template.getClaimStartAt(), template.getClaimEndAt());
    }

    private static CouponView toView(UserCoupon coupon) {
        String status = coupon.getStatus();
        if (CouponStatus.UNUSED.name().equals(status) && coupon.getExpireAt() != null
                && coupon.getExpireAt().isBefore(LocalDateTime.now())) {
            status = CouponStatus.EXPIRED.name();
        }
        return new CouponView(coupon.getId(), coupon.getTitle(), coupon.getThresholdAmount(),
                coupon.getDiscountAmount(), status, coupon.getExpireAt(), coupon.getTradeId());
    }

    public record CouponUse(long id, long templateId, long userId, String title,
                            BigDecimal thresholdAmount, BigDecimal discountAmount) {
    }

    public record CreateTemplate(String title, BigDecimal thresholdAmount, BigDecimal discountAmount,
                                 int total, LocalDateTime claimStartAt, LocalDateTime claimEndAt,
                                 int validDays) {
    }

    private static void fail(String message) {
        throw new BusinessException(ErrorCode.PARAM_ERROR, message);
    }
}
