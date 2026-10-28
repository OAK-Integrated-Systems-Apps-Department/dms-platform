package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.NotificationCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationCategoryRepository extends JpaRepository<NotificationCategory, String> {

    List<NotificationCategory> findByActiveTrueOrderByModuleAscCategoryCodeAsc();
}
