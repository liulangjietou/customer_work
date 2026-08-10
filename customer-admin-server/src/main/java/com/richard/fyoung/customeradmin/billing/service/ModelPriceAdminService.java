package com.richard.fyoung.customeradmin.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.mapper.AiModelPriceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型单价维护。
 *
 * <p><b>只增不改</b>：调价插一条新的生效记录，旧记录留着让历史账单算得回去。
 * 因此这里没有 update——真要改错录的价，删掉那条再插新的。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ModelPriceAdminService {

    private final AiModelPriceMapper priceMapper;

    public ModelPriceAdminService(AiModelPriceMapper priceMapper) {
        this.priceMapper = priceMapper;
    }

    public List<AiModelPrice> list() {
        return priceMapper.selectList(new LambdaQueryWrapper<AiModelPrice>()
            .orderByAsc(AiModelPrice::getProvider)
            .orderByAsc(AiModelPrice::getModelName)
            .orderByDesc(AiModelPrice::getEffectiveFrom));
    }

    public Long create(AiModelPrice request) {
        request.setId(null);
        if (request.getEffectiveFrom() == null) {
            request.setEffectiveFrom(LocalDateTime.now());
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            request.setCurrency("CNY");
        }
        priceMapper.insert(request);
        log.info("model price created, provider={}, model={}, effectiveFrom={}",
            request.getProvider(), request.getModelName(), request.getEffectiveFrom());
        return request.getId();
    }

    public void delete(Long id) {
        priceMapper.deleteById(id);
        log.info("model price deleted, id={}", id);
    }
}
