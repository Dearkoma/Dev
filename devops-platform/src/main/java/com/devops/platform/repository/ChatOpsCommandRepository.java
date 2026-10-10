package com.devops.platform.repository;

import com.devops.platform.entity.ChatOpsCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChatOpsCommandRepository extends JpaRepository<ChatOpsCommand, Long> {
    Optional<ChatOpsCommand> findByCommandAndEnabledTrue(String command);
    List<ChatOpsCommand> findByEnabledTrue();
}
