package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.Voucher;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author zzzhlee
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
