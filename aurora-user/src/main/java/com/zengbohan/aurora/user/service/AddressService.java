package com.zengbohan.aurora.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.user.dto.AddressDtos.AddressRequest;
import com.zengbohan.aurora.user.dto.AddressDtos.AddressView;
import com.zengbohan.aurora.user.entity.UserAddress;
import com.zengbohan.aurora.user.mapper.UserAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AddressService {

    private static final int MAX_ADDRESSES = 20;

    private final UserAddressMapper addressMapper;

    public AddressService(UserAddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    public List<AddressView> list(long userId) {
        return addressMapper.findByUser(userId).stream().map(AddressService::toView).toList();
    }

    public AddressView defaultFor(long userId) {
        UserAddress address = addressMapper.findDefault(userId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "请先添加收货地址");
        }
        return toView(address);
    }

    public AddressView owned(long userId, long addressId) {
        UserAddress address = addressMapper.findOwned(userId, addressId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return toView(address);
    }

    @Transactional
    public long create(long userId, AddressRequest request) {
        if (addressMapper.selectCount(new QueryWrapper<UserAddress>().eq("user_id", userId))
                >= MAX_ADDRESSES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "最多保存 " + MAX_ADDRESSES + " 个地址");
        }
        long existing = addressMapper.selectCount(new QueryWrapper<UserAddress>().eq("user_id", userId));
        UserAddress address = new UserAddress();
        apply(address, request);
        address.setUserId(userId);
        boolean makeDefault = existing == 0 || Boolean.TRUE.equals(request.isDefault());
        address.setIsDefault(makeDefault ? 1 : 0);
        if (makeDefault) {
            clearDefault(userId);
        }
        addressMapper.insert(address);
        return address.getId();
    }

    @Transactional
    public void update(long userId, long addressId, AddressRequest request) {
        UserAddress address = addressMapper.findOwned(userId, addressId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        apply(address, request);
        if (Boolean.TRUE.equals(request.isDefault())) {
            clearDefault(userId);
            address.setIsDefault(1);
        }
        addressMapper.updateById(address);
    }

    @Transactional
    public void delete(long userId, long addressId) {
        UserAddress address = addressMapper.findOwned(userId, addressId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        addressMapper.deleteById(addressId);
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            List<UserAddress> remaining = addressMapper.findByUser(userId);
            if (!remaining.isEmpty()) {
                UserAddress next = remaining.get(0);
                next.setIsDefault(1);
                addressMapper.updateById(next);
            }
        }
    }

    @Transactional
    public void setDefault(long userId, long addressId) {
        UserAddress address = addressMapper.findOwned(userId, addressId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        clearDefault(userId);
        address.setIsDefault(1);
        addressMapper.updateById(address);
    }

    private void clearDefault(long userId) {
        UserAddress patch = new UserAddress();
        patch.setIsDefault(0);
        addressMapper.update(patch, new QueryWrapper<UserAddress>()
                .eq("user_id", userId).eq("is_default", 1));
    }

    private static void apply(UserAddress address, AddressRequest request) {
        address.setReceiverName(request.receiverName().trim());
        address.setPhone(request.phone().trim());
        address.setProvince(request.province().trim());
        address.setCity(request.city().trim());
        address.setDistrict(request.district().trim());
        address.setDetail(request.detail().trim());
        address.setPostalCode(request.postalCode() == null || request.postalCode().isBlank()
                ? null : request.postalCode().trim());
    }

    private static AddressView toView(UserAddress address) {
        return new AddressView(address.getId(), address.getUserId(), address.getReceiverName(),
                address.getPhone(), address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetail(), address.getPostalCode(),
                address.getIsDefault() != null && address.getIsDefault() == 1,
                address.getCreatedAt(), address.getUpdatedAt());
    }
}
