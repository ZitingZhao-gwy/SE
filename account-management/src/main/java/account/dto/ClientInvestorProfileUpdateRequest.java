package account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClientInvestorProfileUpdateRequest {
    @NotBlank
    @JsonProperty("fund_acc_no")
    private String fundAccNo;

    @NotBlank
    @JsonProperty("auth_token")
    private String authToken;

    private String phone;
    private String address;

    @JsonProperty("work_unit")
    private String workUnit;

    private String occupation;
    private String education;
}
