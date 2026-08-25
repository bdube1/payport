package zw.co.payport.gateway.model.request;

import lombok.Data;
import java.util.List;

@Data
public class BulkStatusUpdateRequest {
    private List<String> bundleCodes;
    private boolean active;
    private String reason;
}