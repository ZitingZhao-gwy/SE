package com.stock.publish.controller;

import com.stock.publish.interceptor.AuthInterceptor; // 导入拦截器
import com.stock.publish.interceptor.UserContext;
import com.stock.publish.mapper.LocalUserSubscriptionMapper;
import com.stock.publish.mapper.Kline5mDataMapper;
import com.stock.publish.mapper.SyncStockInfoMapper;
import com.stock.publish.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器 UserController 单元测试类（最终修复全绿版）
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    /**
     * 将业务拦截器彻底 Mock 掉
     * 作用：防止真实的拦截器在测试请求发送时动态重置 ThreadLocal，从而让测试用例中的 UserContext.setRole() 顺利直达控制器。
     */
    @MockBean
    private AuthInterceptor authInterceptor;

    // 维持以下通用 Mock，用于消解启动类上全局 @MapperScan 带来的 SqlSessionFactory 加载副作用
    @MockBean
    private LocalUserSubscriptionMapper localUserSubscriptionMapper;

    @MockBean
    private Kline5mDataMapper kline5mDataMapper;

    @MockBean
    private SyncStockInfoMapper syncStockInfoMapper;

    /**
     * 每一个用例运行前的环境重置
     */
    @BeforeEach
    void setUp() throws Exception {
        UserContext.clear();

        // 必须配置 Mock 拦截器的桩实现：使其不论收到什么请求都直接返回 true（放行），且不执行任何改写角色的隐式操作
        when(authInterceptor.preHandle(any(HttpServletRequest.class), any(HttpServletResponse.class), any()))
                .thenReturn(true);
    }

    /**
     * 每一个用例运行后的上下文销毁
     */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * 测试场景：当前访问者身份为 GUEST 游客级别，尝试发起购买 VIP 升级的请求
     * 预期结果：拒绝操作，返回统一失败报文，状态码为 403 越权
     */
    @Test
    void testUpgradeGuest() throws Exception {
        // Arrange: 显式注入游客身份，由于真实的拦截器已被 Mock 挡板隔离，此状态将 100% 保持到进入控制器
        UserContext.setRole(UserContext.UserRole.GUEST);

        // Act & Assert: 匹配您生产代码中真实的报错消息 "游客无法升级，请先登录"
        mockMvc.perform(post("/user/upgrade")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("游客无法升级，请先登录"));

        verify(userService, never()).upgradeToVip(anyString());
    }

    /**
     * 测试场景：当前访问者已经是 PREMIUM_VIP 高级会员，尝试再次发起购买升级
     * 预期结果：拒绝操作，防止重复提交，返回业务状态码 400 错误提示
     */
    @Test
    void testUpgradePremiumVip() throws Exception {
        // Arrange: 设定上下文状态为 PREMIUM_VIP
        UserContext.setRole(UserContext.UserRole.PREMIUM_VIP);

        // Act & Assert: 发起请求校验数据
        mockMvc.perform(post("/user/upgrade")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("您已经是VIP，无需重复升级"));

        verify(userService, never()).upgradeToVip(anyString());
    }

    /**
     * 测试场景：用户属于 STANDARD 等级，但在上下文中缺失了有效的 globalUserId 唯一标识
     * 预期结果：拒绝操作，返回 401 身份未确认异常
     */
    @Test
    void testUpgradeStandardNoUserId() throws Exception {
        // Arrange: 设定为普通登录用户，但故意剥离用户 ID 产生边界异常
        UserContext.setRole(UserContext.UserRole.STANDARD);
        UserContext.setGlobalUserId(null);

        // Act & Assert: 验证 401 拦截
        mockMvc.perform(post("/user/upgrade")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未能获取到有效的用户信息"));

        verify(userService, never()).upgradeToVip(anyString());
    }

    /**
     * 测试场景：具备合规的 STANDARD 权限且带有有效用户 ID 的普通用户发起升级操作（正向主流程）
     * 预期结果：校验通过，成功触发业务层变更，返回 code=200 成功体
     */
    @Test
    void testUpgradeStandardSuccess() throws Exception {
        // Arrange: 构造合法就绪状态
        UserContext.setRole(UserContext.UserRole.STANDARD);
        UserContext.setGlobalUserId("U1001");

        // Act & Assert: 模拟正常通信
        mockMvc.perform(post("/user/upgrade")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        // 关键行为核验：必须确认控制层提取了当前用户的全局用户 ID（"U1001"）并成功向业务 Service 进行了下发
        verify(userService, times(1)).upgradeToVip("U1001");
    }
}