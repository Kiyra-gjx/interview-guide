# DocumentParseService 测试文档

## 📋 测试概览

本目录包含 `DocumentParseService` 的完整测试套件，包括单元测试和集成测试。

### 测试文件

| 测试类 | 类型 | 测试数量 | 描述 |
|--------|------|---------|------|
| `DocumentParseServiceTest` | 单元测试 | 15+ | 使用 Mock 测试各个方法和边界情况 |
| `DocumentParseIntegrationTest` | 集成测试 | 10+ | 使用真实文件和服务的端到端测试 |

## 🚀 快速开始

### 运行所有测试

```bash
# 在项目根目录运行
cd interview-agent

# 运行所有测试
./gradlew test

# 只运行 DocumentParseService 相关测试
./gradlew test --tests "*DocumentParseService*"
```

### 运行特定测试类

```bash
# 运行单元测试
./gradlew test --tests "interview.guide.infrastructure.file.DocumentParseServiceTest"

# 运行集成测试
./gradlew test --tests "interview.guide.infrastructure.file.DocumentParseIntegrationTest"
```

### 运行特定测试方法

```bash
# 运行单个测试方法
./gradlew test --tests "DocumentParseServiceTest.testParseTxtFile"

# 使用通配符
./gradlew test --tests "DocumentParseServiceTest.testParse*"
```

## 📊 测试覆盖

### DocumentParseServiceTest（单元测试）

#### ✅ 基础功能测试
- `testParseTxtFile` - 解析简单 TXT 文件
- `testParseMarkdownFile` - 解析 Markdown 文件
- `testParseFromByteArray` - 从字节数组解析
- `testParseEmptyFile` - 空文件处理

#### ✅ 特殊场景测试
- `testParseFileWithSpecialCharacters` - 特殊字符处理
- `testParseChineseResume` - 中文内容解析
- `testParseDocumentWithUrls` - URL 处理

#### ✅ 集成测试
- `testDownloadAndParseContent` - 下载并解析
- `testDownloadAndParseContentFailure` - 下载失败处理
- `testDownloadAndParseEmptyContent` - 空内容处理

#### ✅ 验证测试
- `testTextCleaningServiceIsCalled` - 服务调用验证
- `testIntegrationWithRealFile` - 真实文件集成测试

#### ✅ 性能测试
- `testPerformanceWithMultipleFiles` - 批量文件解析性能

#### ✅ 异常测试
- `testParseFailureWithIOException` - IO 异常处理

### DocumentParseIntegrationTest（集成测试）

#### ✅ 文件格式测试
- `testParseTxtResume` - TXT 格式简历解析
- `testParseMarkdownResume` - Markdown 格式简历解析

#### ✅ 内容验证测试
- `testParseTextWithSpecialCharacters` - 特殊字符验证
- `testParseMultilingualText` - 多语言混合文本
- `testTextCleaningIntegration` - 文本清理效果验证

#### ✅ 性能测试
- `testLargeFilePerformance` - 大文件解析性能（50KB+）

#### ✅ 边界测试
- `testEmptyContentHandling` - 空内容处理
- `testNoiseOnlyDocument` - 纯噪音文档处理

## 🧪 测试数据

### 测试资源文件位置
```
app/src/test/resources/test-files/
├── README.md              # 测试文件说明
├── sample-resume.txt      # TXT 格式简历样例
└── sample-resume.md       # Markdown 格式简历样例
```

### 测试数据特点
- ✅ 真实的简历结构
- ✅ 包含中英文内容
- ✅ 包含特殊字符和 Emoji
- ✅ 包含 URL 和联系方式
- ✅ 包含需要清理的噪音（分隔线、图片链接等）

## 📈 测试报告

### 查看测试报告

测试运行后，可以在以下位置查看详细报告：

```
app/build/reports/tests/test/index.html
```

在浏览器中打开即可查看：
- 测试通过率
- 执行时间
- 失败详情
- 代码覆盖率（如果启用）

### 生成测试覆盖率报告

```bash
# 运行测试并生成覆盖率报告
./gradlew test jacocoTestReport

# 查看报告
open app/build/reports/jacoco/test/html/index.html
```

## 🔍 测试要点

### 1. 文件格式支持
- [x] TXT - 纯文本
- [x] MD - Markdown
- [ ] PDF - 需要真实 PDF 文件测试
- [ ] DOCX - 需要真实 Word 文件测试
- [ ] DOC - 需要真实 Word 文件测试

### 2. 文本清理验证
- [x] 分隔线清理（`---`, `===`, `***`）
- [x] 图片文件名清理（`image123.png`）
- [x] 临时文件路径清理（`file:///tmp/...`）
- [x] 图片链接清理（`https://example.com/image.png`）
- [x] 控制字符清理
- [x] 连续空行压缩
- [x] 行尾空格清理

### 3. 字符编码
- [x] UTF-8 编码（中文、日文、韩文）
- [x] 特殊字符（Emoji）
- [x] 标点符号

### 4. 边界情况
- [x] 空文件
- [x] 纯空白文件
- [x] 纯噪音文件（只有需要清理的内容）
- [x] 大文件（50KB+）
- [ ] 超大文件（5MB，达到限制）

### 5. 异常处理
- [x] IO 异常
- [x] 文件不存在
- [x] 下载失败
- [ ] Tika 解析异常
- [ ] 超过大小限制

## 🎯 添加新测试

### 添加单元测试示例

```java
@Test
@DisplayName("你的测试描述")
void testYourFeature() {
    // Given: 准备测试数据
    String content = "测试内容";
    MultipartFile file = new MockMultipartFile(
        "file",
        "test.txt",
        "text/plain",
        content.getBytes(StandardCharsets.UTF_8)
    );

    // When: 执行测试
    String result = documentParseService.parseContent(file);

    // Then: 验证结果
    assertNotNull(result);
    assertTrue(result.contains("期望内容"));
}
```

### 添加集成测试示例

```java
@Test
@DisplayName("集成测试 - 你的测试描述")
void testYourIntegration() throws IOException {
    // Given: 从资源加载真实文件
    InputStream inputStream = getClass()
        .getResourceAsStream("/test-files/your-file.txt");
    byte[] content = inputStream.readAllBytes();
    
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "your-file.txt",
        "text/plain",
        content
    );

    // When
    String result = documentParseService.parseContent(file);

    // Then
    assertNotNull(result);
    // 添加你的验证逻辑
}
```

## 🐛 调试技巧

### 1. 查看解析结果

在测试中打印解析结果：

```java
System.out.println("\n========== 解析结果 ==========");
System.out.println(result);
System.out.println("字符数: " + result.length());
```

### 2. 比对清理前后

```java
String rawContent = "原始内容...";
String cleanedContent = textCleaningService.cleanText(rawContent);

System.out.println("清理前: " + rawContent);
System.out.println("清理后: " + cleanedContent);
```

### 3. 使用断点调试

在 IDE 中：
1. 在测试方法打断点
2. 右键选择 "Debug"
3. 单步执行查看变量值

## 📝 注意事项

### 1. Mock vs 真实服务
- **单元测试**: 使用 Mock 隔离依赖，快速验证逻辑
- **集成测试**: 使用真实服务，验证完整流程

### 2. 测试资源管理
- 测试文件放在 `test/resources/test-files/`
- 不要提交过大的测试文件（< 1MB）
- 使用虚构数据，不要使用真实个人信息

### 3. 性能测试
- 性能测试结果会因机器而异
- 设置合理的超时阈值
- 关注相对性能而非绝对值

### 4. 测试隔离
- 每个测试方法应该独立运行
- 使用 `@BeforeEach` 初始化状态
- 不要依赖测试执行顺序

## 🔗 相关文档

- [Apache Tika 文档](https://tika.apache.org/documentation.html)
- [JUnit 5 用户指南](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito 文档](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

## ✅ 测试清单

在提交代码前，请确保：

- [ ] 所有测试通过
- [ ] 新功能有对应的测试
- [ ] 边界情况有覆盖
- [ ] 异常处理有测试
- [ ] 测试代码有适当的注释
- [ ] 测试报告无警告
- [ ] 代码覆盖率 > 80%（如果项目有要求）

## 🤝 贡献指南

如果你添加了新的测试或改进了现有测试：

1. 确保测试描述清晰（使用 `@DisplayName`）
2. 遵循 Given-When-Then 结构
3. 添加必要的注释说明测试目的
4. 更新本 README 文档
5. 提交时说明测试覆盖的场景
