package com.devops.agent.controller;

import com.devops.agent.common.dto.ApiResponse;
import com.devops.agent.domain.biz.entity.TeamMember;
import com.devops.agent.domain.biz.service.TeamMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 团队成员（用户）接口
 * <p>
 * 对应 A2 决策（用户拍板「后端新增 /users 接口」）：工单负责人名单不再由前端硬编码。
 * </p>
 * <p>
 * 背景：前端 {@code ASSIGNEE_OPTIONS = ['张明','李四','王五','赵六','孙七','周八','待分配']}
 * 是编造名单——库里只有「张明」一个真实负责人，其余五人从不存在。用户选人后写入
 * {@code sys_devops_ticket.assignee}（自由文本），工单被指派给不存在的人，
 * 且筛选下拉框恒定七项不随真实数据变化。
 * </p>
 * <p>
 * 注意：L1 阶段无真实鉴权（见 CLAUDE.md {@code TODO(P2-鉴权)}），本接口返回的是
 * <b>运维团队成员名录</b>而非登录账号体系。真实鉴权落地后应与账号表合并。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
@RestController
@RequestMapping("/api/v1/users")
public class TeamMemberController {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberController.class);

    private final TeamMemberService teamMemberService;

    public TeamMemberController(TeamMemberService teamMemberService) {
        this.teamMemberService = teamMemberService;
    }

    /**
     * 查询可指派成员名录
     *
     * @param includeDisabled 是否包含已停用成员，默认 false
     * @return {@code {total, users:[{id,name,email,role,title,status,sortOrder,activeTicketCount}]}}
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        List<TeamMember> members = teamMemberService.listAssignableMembers(includeDisabled);
        return ApiResponse.success(Map.of(
                "total", members.size(),
                "users", members
        ));
    }
}
