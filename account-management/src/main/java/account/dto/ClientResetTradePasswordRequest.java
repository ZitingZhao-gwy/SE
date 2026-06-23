package account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClientResetTradePasswordRequest {
    @NotBlank @JsonProperty("fund_acc_no") private String fundAccNo;
    @NotBlank @JsonProperty("id_number") private String idNumber;
    @NotBlank @JsonProperty("new_password") private String newPassword;
}
