package zw.co.payport.gateway.entity;
import jakarta.persistence.*;
import lombok.*;

@Data @Entity @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "network_prefixes")
public class NetworkPrefix {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 10) private String prefix;
    @Column(name = "network_code", nullable = false, length = 20) private String networkCode;
    @Column(name = "network_name", nullable = false, length = 50) private String networkName;
    @Column(name = "country_code", nullable = false, length = 2, columnDefinition = "VARCHAR(2)") private String countryCode;
}
