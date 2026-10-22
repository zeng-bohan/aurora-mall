package com.zengbohan.aurora.order.client;

import com.zengbohan.aurora.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "aurora-user", contextId = "userAdminClient")
public interface UserAdminClient {

    record UserView(long id, String username, String nickname, String role,
                    int status, LocalDateTime createdAt) {
    }

    @GetMapping("/internal/users/list")
    Result<List<UserView>> users();
}
