package com.devops.agent.controller.dto;

import com.devops.agent.domain.biz.entity.TeamMember;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "团队成员列表响应")
public record TeamMemberListResponse(
        @Schema(description = "成员总数")
        int total,
        @Schema(description = "成员列表")
        List<TeamMember> users
) {
}
