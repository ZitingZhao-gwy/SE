package com.stock.publish.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.stock.publish.entity.LocalUserSubscription;
import com.stock.publish.mapper.LocalUserSubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

/**
 * 业务实现类 UserServiceImpl 单元测试类
 * 通过纯 Mockito 架构将业务逻辑与具体的事务管理器以及物理数据库解耦
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    // Mock 用户订阅持久层
    @Mock
    private LocalUserSubscriptionMapper subscriptionMapper;

    // 将 Mock 对象注入业务实现类中
    @InjectMocks
    private UserServiceImpl userService;

    /**
     * 测试场景：升级 VIP 时，该用户在本地数据库的行记录本身就是完整存在的
     * 预期结果：MyBatis-Plus 的 update 方法顺利执行并返回影响行数 1，业务层不再触发多余的插入操作
     */
    @Test
    void testUpgradeToVipRowExists() {
        // Arrange: 确定待升级用户
        String globalUserId = "U1001";

        // 桩实现：模拟底层 update 操作成功修改了 1 条行记录 (代表记录存在且更新成功)
        when(subscriptionMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(1);

        // Act: 调用业务层执行方法
        userService.upgradeToVip(globalUserId);

        // Assert 行为核验:
        // 1. 验证是否调用了底层的通用更新方法
        verify(subscriptionMapper, times(1)).update(eq(null), any(UpdateWrapper.class));
        // 2. 既然更新成功，绝对不需要、也禁止调用 insert 兜底策略
        verify(subscriptionMapper, never()).insert(any(LocalUserSubscription.class));
    }

    /**
     * 测试场景：升级 VIP 时，本地由于某些分布式边界异常或历史遗留缺失了该用户的这一行基础记录
     * 预期结果：MyBatis-Plus 的 update 方法返回影响行数 0。业务层成功识别此状态并自动触发兜底插入，确保系统高可用不崩溃
     */
    @Test
    void testUpgradeToVipRowNotExists() {
        // Arrange: 确定待升级用户
        String globalUserId = "U1001";

        // 桩实现：模拟底层 update 操作受影响行数为 0 (代表数据库里根本没有找到 global_user_id='U1001' 的这一行数据)
        when(subscriptionMapper.update(eq(null), any(UpdateWrapper.class))).thenReturn(0);

        // Act: 调用业务层执行方法
        userService.upgradeToVip(globalUserId);

        // Assert 行为核验:
        // 1. 验证确实首先尝试了 update 操作
        verify(subscriptionMapper, times(1)).update(eq(null), any(UpdateWrapper.class));
        // 2. 关键断言：因为更新失败，业务层必须立即切换为数据兜底策略，调用 insert 方法将该用户直接以 VIP 状态完成显式初始化
        verify(subscriptionMapper, times(1)).insert(any(LocalUserSubscription.class));
    }
}