package com.stock.publish.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.publish.entity.LocalUserSubscription;
import com.stock.publish.mapper.LocalUserSubscriptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 拦截器 AuthInterceptor 单元测试类
 * 使用 MockitoExtension 启用 Mockito 注解支持，无需启动完整的 Spring 容器，提高测试执行效率
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    // Mock 本地用户订阅表的持久层，切断对真实 MySQL 数据库的依赖
    @Mock
    private LocalUserSubscriptionMapper subscriptionMapper;

    // 自动将上述 Mock 的 subscriptionMapper 注入到被测试的目标拦截器实例中
    @InjectMocks
    private AuthInterceptor authInterceptor;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /**
     * 在每个测试用例执行前的初始化操作
     */
    @BeforeEach
    void setUp() {
        // 使用 Spring 提供的 Mock 对象模拟 HTTP 请求和响应体
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        // 关键安全点：测试开始前必须强制清理 ThreadLocal 上下文，防止并发测试或前置用例造成的污染
        UserContext.clear();
    }

    /**
     * 在每个测试用例执行后的清理操作
     */
    @AfterEach
    void tearDown() {
        // 请求生命周期结束，销毁上下文中的数据，避免线程池复用导致内存泄漏
        UserContext.clear();
    }

    /**
     * 测试场景：请求未携带任何 Authorization 请求头（匿名访问）
     * 预期结果：拦截器放行（返回 true），且用户角色被静默降级为 GUEST 游客
     */
    @Test
    void testPreHandleNoToken() {
        // Act: 执行被测方法
        boolean result = authInterceptor.preHandle(request, response, new Object());

        // Assert: 验证断言
        assertTrue(result, "拦截器应当对未带 Token 的请求予以放行，交由具体 Controller 处理权限层级");
        assertEquals(UserContext.UserRole.GUEST, UserContext.getRole(), "未带 Token 时，系统权限应降级为 GUEST 游客级别");
        assertNull(UserContext.getGlobalUserId(), "游客状态下，全局用户 ID 应当为 null");
    }

    /**
     * 测试场景：请求携带了不合法的伪造 Token（"Bearer invalid_token"）
     * 预期结果：拦截器放行，外部 Mock 鉴权失败，系统静默降级为 GUEST
     */
    @Test
    void testPreHandleInvalidToken() {
        // Arrange: 构造不合法的 Token Header
        request.addHeader("Authorization", "Bearer invalid_token");

        // Act: 执行被测方法
        boolean result = authInterceptor.preHandle(request, response, new Object());

        // Assert: 验证鉴权失败时的降级行为
        assertTrue(result, "外部鉴权失败时，系统不应抛出 401 中断，需放行后依靠业务层降级渲染数据");
        assertEquals(UserContext.UserRole.GUEST, UserContext.getRole(), "非法 Token 对应的身份应转换为 GUEST");
        assertNull(UserContext.getGlobalUserId(), "鉴权未通过，上下文不应存入全局用户 ID");
    }

    /**
     * 测试场景：请求携带有效 Token，但外部账户系统判定其未绑定数字证书（"Bearer no_cert_token"）
     * 预期结果：拦截器放行，根据契约设计静默降级为 GUEST
     */
    @Test
    void testPreHandleNoCertToken() {
        // Arrange: 模拟未绑定证书的特殊 Token
        request.addHeader("Authorization", "Bearer no_cert_token");

        // Act: 执行被测方法
        boolean result = authInterceptor.preHandle(request, response, new Object());

        // Assert: 验证证书校验不通过时的降级处理
        assertTrue(result, "证书未绑定时同样执行放行逻辑");
        assertEquals(UserContext.UserRole.GUEST, UserContext.getRole(), "根据设计契约，未绑定证书的用户等价于 GUEST 游客处理");
        assertNull(UserContext.getGlobalUserId(), "非合规用户，全局用户 ID 保持为空");
    }

    /**
     * 测试场景：合法用户首次登录系统，本地 local_user_subscription 表中不存在该用户的订阅记录
     * 预期结果：拦截器自动在本地库执行初始化插入，设定为普通用户（STANDARD），存入上下文并放行
     */
    @Test
    void testPreHandleValidTokenNewUser() {
        // Arrange: 构造合规 Token 报文
        request.addHeader("Authorization", "Bearer valid_token");

        // 桩实现（Stubing）：当通过条件构造器查询本地订阅表时，模拟返回 null（代表该用户是第一次访问本系统的全新用户）
        when(subscriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // Act: 执行被测方法
        boolean result = authInterceptor.preHandle(request, response, new Object());

        // Assert: 验证新用户的自动化注册与评级
        assertTrue(result);
        assertEquals(UserContext.UserRole.STANDARD, UserContext.getRole(), "新注册用户在本地默认拥有 STANDARD 普通用户权限");
        assertEquals("F0001", UserContext.getGlobalUserId(), "成功提取外部接口契约约定的 Mock 用户 ID : U1001");

        // 行为验证：必须确保拦截器内部调用了 mapper.insert 方法，为新用户在 MySQL 建立了基础行记录
        verify(subscriptionMapper, times(1)).insert(any(LocalUserSubscription.class));
    }

    /**
     * 测试场景：合法老用户登录系统，本地 local_user_subscription 表中已经存在其购买增值服务的 VIP 记录
     * 预期结果：成功查库，判定为 PREMIUM_VIP 级别，不再重复执行数据插入操作
     */
    @Test
    void testPreHandleValidTokenVipUser() {
        // Arrange: 构造合规 Token
        request.addHeader("Authorization", "Bearer valid_token");

        // 构造本地数据库已有的订阅行对象
        LocalUserSubscription subscription = new LocalUserSubscription();
        subscription.setGlobalUserId("F0001");
        subscription.setIsPremium(true); // 标记此人已购买 VIP 服务

        // 桩实现：模拟查库返回该 VIP 用户记录
        when(subscriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subscription);

        // Act: 执行
        boolean result = authInterceptor.preHandle(request, response, new Object());

        // Assert: 验证高级权限提取
        assertTrue(result);
        assertEquals(UserContext.UserRole.PREMIUM_VIP, UserContext.getRole(), "本地库 is_premium 为 true，应判定为 PREMIUM_VIP");
        assertEquals("F0001", UserContext.getGlobalUserId());

        // 行为验证：既然老用户记录已存在，拦截器绝对不能再次执行 insert 插入操作，否则会造成主键/唯一索引冲突
        verify(subscriptionMapper, never()).insert(any(LocalUserSubscription.class));
    }

    /**
     * 测试场景：HTTP 请求生命周期彻底结束（即到达 afterCompletion 钩子方法）
     * 预期结果：强行擦除 ThreadLocal 中的变量，防止引发线程池内存泄漏与串号风险
     */
    @Test
    void testAfterCompletion() {
        // Arrange: 先为主线程上下文模拟填入用户信息
        UserContext.setGlobalUserId("F0001");
        UserContext.setRole(UserContext.UserRole.STANDARD);

        // Act: 调用生命周期销毁钩子
        authInterceptor.afterCompletion(request, response, new Object(), null);

        // Assert: 验证数据清除后归于初始空状态
        assertNull(UserContext.getGlobalUserId(), "请求结束后，全局用户 ID 必须被彻底移除");
        assertEquals(UserContext.UserRole.GUEST, UserContext.getRole(), "上下文清理后，角色权限应当恢复为默认的 GUEST 状态");
    }
}