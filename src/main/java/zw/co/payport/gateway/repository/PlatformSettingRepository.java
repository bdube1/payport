package zw.co.payport.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import zw.co.payport.gateway.entity.PlatformSetting;

@Repository
public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {
}
