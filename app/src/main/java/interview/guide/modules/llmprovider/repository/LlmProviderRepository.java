package interview.guide.modules.llmprovider.repository;

import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LlmProviderRepository extends JpaRepository<LlmProviderEntity, String> {

    List<LlmProviderEntity> findByEnabledTrue();
}
