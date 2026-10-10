package com.devops.platform.repository;

import com.devops.platform.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    /** 找订阅某个事件的所有激活 webhook (项目过滤在 WebhookDispatcher.matchesProject 中完成) */
    List<WebhookSubscription> findByEventTypeAndActiveTrue(String eventType);

    List<WebhookSubscription> findByActiveTrue();
}
