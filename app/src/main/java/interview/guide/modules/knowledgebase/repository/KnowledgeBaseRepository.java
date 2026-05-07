package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库Repository
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    /**
     * 根据文件哈希查找知识库（用于去重）
     */
    Optional<KnowledgeBaseEntity> findByFileHash(String fileHash);

    Optional<KnowledgeBaseEntity> findByIdAndLifecycleStatus(Long id, KnowledgeBaseLifecycleStatus lifecycleStatus);

    @Query("""
        SELECT k.lifecycleStatus
        FROM KnowledgeBaseEntity k
        WHERE k.id = :id
        """)
    Optional<KnowledgeBaseLifecycleStatus> findLifecycleStatusById(@Param("id") Long id);

    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    /**
     * 按上传时间倒序查找所有知识库
     */
    List<KnowledgeBaseEntity> findAllByOrderByUploadedAtDesc();

    List<KnowledgeBaseEntity> findByLifecycleStatusOrderByUploadedAtDesc(KnowledgeBaseLifecycleStatus lifecycleStatus);

    /**
     * 获取所有不同的分类
     */
    @Query("""
        SELECT DISTINCT k.category FROM KnowledgeBaseEntity k
        WHERE k.category IS NOT NULL
          AND k.lifecycleStatus = interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus.ACTIVE
        ORDER BY k.category
        """)
    List<String> findAllCategories();

    /**
     * 根据分类查找知识库
     */
    List<KnowledgeBaseEntity> findByCategoryOrderByUploadedAtDesc(String category);

    List<KnowledgeBaseEntity> findByCategoryAndLifecycleStatusOrderByUploadedAtDesc(
        String category,
        KnowledgeBaseLifecycleStatus lifecycleStatus
    );

    /**
     * 查找未分类的知识库
     */
    List<KnowledgeBaseEntity> findByCategoryIsNullOrderByUploadedAtDesc();

    List<KnowledgeBaseEntity> findByCategoryIsNullAndLifecycleStatusOrderByUploadedAtDesc(KnowledgeBaseLifecycleStatus lifecycleStatus);

    /**
     * 按名称或文件名模糊搜索（不区分大小写）
     */
    @Query("""
        SELECT k FROM KnowledgeBaseEntity k
        WHERE k.lifecycleStatus = interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus.ACTIVE
          AND (LOWER(k.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(k.originalFilename) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY k.uploadedAt DESC
        """)
    List<KnowledgeBaseEntity> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 按文件大小排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByFileSizeDesc();

    /**
     * 按访问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByAccessCountDesc();

    /**
     * 按提问次数排序
     */
    List<KnowledgeBaseEntity> findAllByOrderByQuestionCountDesc();

    // ==================== 批量更新 ====================

    /**
     * 批量增加知识库提问计数
     * @param ids 知识库ID列表
     * @return 更新的行数
     */
    @Modifying
    @Query("""
        UPDATE KnowledgeBaseEntity k
        SET k.questionCount = k.questionCount + 1
        WHERE k.id IN :ids
          AND k.lifecycleStatus = interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus.ACTIVE
        """)
    int incrementQuestionCountBatch(@Param("ids") List<Long> ids);

    // ==================== 统计查询 ====================

    /**
     * 统计总提问次数
     */
    @Query("""
        SELECT COALESCE(SUM(k.questionCount), 0)
        FROM KnowledgeBaseEntity k
        WHERE k.lifecycleStatus = interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus.ACTIVE
        """)
    long sumQuestionCount();

    /**
     * 统计总访问次数
     */
    @Query("""
        SELECT COALESCE(SUM(k.accessCount), 0)
        FROM KnowledgeBaseEntity k
        WHERE k.lifecycleStatus = interview.guide.modules.knowledgebase.model.KnowledgeBaseLifecycleStatus.ACTIVE
        """)
    long sumAccessCount();

    /**
     * 按向量化状态统计数量
     */
    long countByVectorStatus(VectorStatus vectorStatus);

    long countByVectorStatusAndLifecycleStatus(VectorStatus vectorStatus, KnowledgeBaseLifecycleStatus lifecycleStatus);

    /**
     * 按向量化状态查找知识库（按上传时间倒序）
     */
    List<KnowledgeBaseEntity> findByVectorStatusOrderByUploadedAtDesc(VectorStatus vectorStatus);

    List<KnowledgeBaseEntity> findByVectorStatusAndLifecycleStatusOrderByUploadedAtDesc(
        VectorStatus vectorStatus,
        KnowledgeBaseLifecycleStatus lifecycleStatus
    );

    long countByLifecycleStatus(KnowledgeBaseLifecycleStatus lifecycleStatus);
}

