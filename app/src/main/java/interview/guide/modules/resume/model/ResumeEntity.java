package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 简历实体。
 */
@Entity
@Table(name = "resumes", indexes = {
    @Index(name = "idx_resume_hash", columnList = "fileHash", unique = true)
})
public class ResumeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String fileHash;

    @Column(nullable = false)
    private String originalFilename;

    private Long fileSize;

    private String contentType;

    @Column(length = 500)
    private String storageKey;

    @Column(length = 1000)
    private String storageUrl;

    @Column(columnDefinition = "TEXT")
    private String resumeText;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    private LocalDateTime lastAccessedAt;

    private Integer accessCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus analyzeStatus = AsyncTaskStatus.PENDING;

    @Column(length = 500)
    private String analyzeError;

    @Column(length = 100)
    private String analyzeErrorCode;

    private Boolean analyzeRetryable;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStorageUrl() {
        return storageUrl;
    }

    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }

    public String getResumeText() {
        return resumeText;
    }

    public void setResumeText(String resumeText) {
        this.resumeText = resumeText;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public Integer getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }

    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public AsyncTaskStatus getAnalyzeStatus() {
        return analyzeStatus;
    }

    public void setAnalyzeStatus(AsyncTaskStatus analyzeStatus) {
        this.analyzeStatus = analyzeStatus;
    }

    public String getAnalyzeError() {
        return analyzeError;
    }

    public void setAnalyzeError(String analyzeError) {
        this.analyzeError = analyzeError;
    }

    public String getAnalyzeErrorCode() {
        return analyzeErrorCode;
    }

    public void setAnalyzeErrorCode(String analyzeErrorCode) {
        this.analyzeErrorCode = analyzeErrorCode;
    }

    public Boolean getAnalyzeRetryable() {
        return analyzeRetryable;
    }

    public void setAnalyzeRetryable(Boolean analyzeRetryable) {
        this.analyzeRetryable = analyzeRetryable;
    }
}