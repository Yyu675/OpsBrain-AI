package com.devops.agent.domain.biz.service;

import com.devops.agent.domain.biz.entity.TeamMember;
import com.devops.agent.domain.biz.repository.TeamMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 运维团队成员服务
 * <p>
 * 工单负责人名录的编排层：名录成员 + 历史负责人合并，并装填工单负载。
 * </p>
 * <p>
 * 对应 A2 决策（用户拍板「后端新增 /users 接口」）：前端不再硬编码
 * {@code ASSIGNEE_OPTIONS} 编造名单，改由本服务下发真实数据。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-08-17
 */
@Service
public class TeamMemberService {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberService.class);

    private final TeamMemberRepository memberRepository;

    public TeamMemberService(TeamMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * 查询可指派成员名录
     * <p>
     * 返回内容 = 名录表成员 + 仅存在于工单表的历史负责人。
     * 后者标记 {@code status=LEGACY}，前端可据此区分「在册成员」与「历史负责人」——
     * 不下发会导致下拉框选不中当前负责人，用户误以为工单未指派。
     * </p>
     *
     * @param includeDisabled 是否包含已停用成员
     * @return 成员列表（含 activeTicketCount 负载）
     */
    public List<TeamMember> listAssignableMembers(boolean includeDisabled) {
        List<TeamMember> members = new ArrayList<>(memberRepository.findAll(includeDisabled));

        // 历史负责人补齐：工单指派过但不在名录的人
        for (String legacyName : memberRepository.findLegacyAssigneeNames()) {
            TeamMember legacy = new TeamMember();
            legacy.setName(legacyName);
            legacy.setRole("operator");
            // LEGACY 而非 ACTIVE：如实标注「不在册但历史工单指派过」，
            // 谎报为在册成员会让运维以为此人仍在团队
            legacy.setStatus("LEGACY");
            legacy.setSortOrder(999);
            members.add(legacy);
        }

        // 负载装填：一次聚合查询，避免每人单查（N+1）
        Map<String, Integer> workload = memberRepository.countActiveTicketsByAssignee();
        for (TeamMember m : members) {
            m.setActiveTicketCount(workload.getOrDefault(m.getName(), 0));
        }

        log.info("[TeamMemberService] 名录查询完成 | 在册+历史={} 人 | includeDisabled={}",
                members.size(), includeDisabled);
        return members;
    }
}
