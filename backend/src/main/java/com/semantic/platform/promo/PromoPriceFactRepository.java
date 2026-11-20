package com.semantic.platform.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PromoPriceFactRepository extends JpaRepository<PromoPriceFact, Long> {
    List<PromoPriceFact> findAllByOrderByIdAsc();
    List<PromoPriceFact> findBySkuAndStoreIdOrderByValidFromAsc(String sku, String storeId);
}
