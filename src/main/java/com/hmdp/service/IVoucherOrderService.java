package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.dto.Result;
import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderService extends IService<VoucherOrder>  {

    Result purchaseSecKillVoucher(Long voucherId);

    Result purchaseSecKillVoucherOfOneOly(Long voucherId, Long userId);

//    Result purchaseSecKillVoucherByRedis(Long voucherId) throws InterruptedException;
}
