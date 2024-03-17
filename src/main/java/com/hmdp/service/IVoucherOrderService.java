package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderService extends IService<VoucherOrder>  {

    Result purchaseSecKillVoucher(Long voucherId);

//    Result purchaseSecKillVoucherByRedis(Long voucherId) throws InterruptedException;
}
