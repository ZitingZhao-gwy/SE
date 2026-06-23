package account.controller.external;

import account.common.Result;
import account.common.ResultPayloadMapper;
import account.dto.ClientChangeFundPasswordRequest;
import account.dto.ClientDepositRequest;
import account.dto.ClientLoginAuthRequest;
import account.dto.ClientWithdrawRequest;
import account.dto.ClientInvestorProfileUpdateRequest;
import account.dto.ClientResetTradePasswordRequest;
import account.dto.CompleteLoginCertificateRequest;
import account.service.api.FundAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/external/fund")
@RequiredArgsConstructor
public class ExternalFundController {

    private final FundAccountService fundAccountService;
    private final ObjectMapper objectMapper;

    @PostMapping("/login")
    public Result<Void> clientLoginAuth(@Valid @RequestBody ClientLoginAuthRequest request) {
        log.info("[clientLoginAuth] fund_acc_no={}", request.getFundAccNo());
        return ResultPayloadMapper.flatten(
                objectMapper,
                fundAccountService.clientLoginAuth(request.getFundAccNo(), request.getTradePassword()),
                "登录成功"
        );
    }

    @PostMapping("/complete-certificate")
    public Result<Void> completeCertificate(@Valid @RequestBody CompleteLoginCertificateRequest request) {
        log.info("[completeFundCertificate] subject_key={}", request.getSubjectKey());
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.completeLoginCertificate(request), "认证成功");
    }

    @GetMapping("/snapshot")
    public Result<Void> getFundSnapshot(
            @RequestParam("fund_acc_no") @NotBlank String fundAccNo,
            @RequestParam("auth_token") @NotBlank String authToken) {
        log.info("[getFundSnapshot] fund_acc_no={}", fundAccNo);
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.getFundSnapshot(fundAccNo, authToken), "查询成功");
    }

    @GetMapping("/profile")
    public Result<Void> getClientProfile(
            @RequestParam("fund_acc_no") @NotBlank String fundAccNo,
            @RequestParam("auth_token") @NotBlank String authToken) {
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.getClientInvestorProfile(fundAccNo, authToken), "profile loaded");
    }

    @PutMapping("/profile")
    public Result<Void> updateClientProfile(@Valid @RequestBody ClientInvestorProfileUpdateRequest request) {
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.updateClientInvestorProfile(request), "profile updated");
    }

    @PostMapping("/reset-trade-password")
    public Result<Void> resetClientTradePassword(@Valid @RequestBody ClientResetTradePasswordRequest request) {
        fundAccountService.resetClientTradePassword(request);
        return Result.success("password reset succeeded");
    }

    @PostMapping("/deposit")
    public Result<Void> clientDeposit(@Valid @RequestBody ClientDepositRequest request) {
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.clientDeposit(request), "deposit succeeded");
    }

    @PostMapping("/withdraw")
    public Result<Void> clientWithdraw(@Valid @RequestBody ClientWithdrawRequest request) {
        return ResultPayloadMapper.flatten(objectMapper, fundAccountService.clientWithdraw(request), "withdraw succeeded");
    }

    @PutMapping("/password")
    public Result<Void> clientChangeFundPassword(@Valid @RequestBody ClientChangeFundPasswordRequest request) {
        log.info("[clientChangeFundPassword] fund_acc_no={} password_type={}",
                request.getFundAccNo(), request.getPasswordType());
        fundAccountService.clientChangeFundPassword(request);
        return Result.success("密码修改成功");
    }
}
