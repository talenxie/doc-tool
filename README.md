# Doc-Tool 文档处理工具

基于 Spring Boot 3.2 + MyBatis + Thymeleaf 的在线文档处理平台，提供文档翻译、PDF 转换和 OCR 识别三大核心功能。

## 功能特性

- **文档翻译** — 上传 DOC/DOCX 文件，自动翻译全文为中文，保留原始排版（字号、加粗、居中、缩进等），翻译完成后自动清理评估水印
- **PDF 转 DOC** — 将 PDF 文件提取文本内容并转换为可编辑的 Word 文档
- **图片 OCR 转 DOC** — OCR 识别图片中的文字，生成 Word 文档，尽量还原原始版式。支持中英文识别，采用异步任务机制，前端轮询获取结果

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5, Spring MVC |
| 持久层 | MyBatis 3.0.3, H2 嵌入式数据库 |
| 模板引擎 | Thymeleaf |
| 文档处理 | Apache POI 5.2.5, Apache PDFBox 3.0.2 |
| OCR 引擎 | RapidOCR 0.0.7（PaddleOCR ONNX，中文识别准确率高，纯本地运行） |
| OCR 回退 | Tess4J 5.11.0（Tesseract 封装） |
| 工具库 | Lombok |
| 构建工具 | Maven, 静态资源内容哈希（HashAssets） |

## 环境要求

- JDK 17+
- Maven 3.6+

## 快速开始

### 1. OCR 引擎配置（图片转 DOC 功能）

默认使用内置的 RapidOCR（PaddleOCR ONNX 模型，随 jar 打包，无需额外安装，支持 Windows/Linux x86_64）。

若 RapidOCR 加载失败会自动回退到 Tesseract，需自行安装并通过环境变量配置数据路径：

```bash
# Windows
set TESSDATA_PATH=D:\Program Files\Tesseract-OCR\tessdata

# Linux
export TESSDATA_PATH=/usr/share/tessdata
```

也可直接运行 `install-tesseract.bat`（Windows）安装 Tesseract。

### 2. 编译运行

**方式一：打包运行**

```bash
mvn clean package -DskipTests
java -jar target\doc-tool-1.0.0.jar
```

**方式二：Maven 插件直接运行**

```bash
mvn spring-boot:run
```

**方式三：使用启动脚本（Windows）**

```bash
start.bat
```

启动脚本会自动检查端口占用、编译项目并启动应用。

### 3. 访问应用

打开浏览器访问：http://localhost:8081

## 页面说明

| 路径 | 功能 |
|------|------|
| `/` | 首页，展示任务历史记录 |
| `/translate` | 文档翻译页面 |
| `/pdf-convert` | PDF 转 Word 页面 |
| `/ocr` | 图片 OCR 识别页面 |
| `/h2-console` | H2 数据库管理控制台 |

## 项目结构

```
src/main/java/com/doctool/
├── DocToolApplication.java          # 启动类
├── config/
│   └── CorsConfig.java              # 跨域配置
├── controller/
│   ├── HomeController.java          # 页面路由
│   ├── TranslateController.java     # 翻译 API
│   ├── PdfConvertController.java    # PDF 转换 API
│   └── OcrController.java           # OCR API
├── mapper/
│   └── TaskRecordMapper.java        # 任务记录 Mapper
├── model/
│   ├── TaskRecord.java              # 任务记录实体
│   └── OcrLine.java                 # OCR 行数据模型
├── service/
│   ├── TranslateService.java        # 翻译业务（Google Translate API）
│   ├── PdfConvertService.java       # PDF 转换业务
│   └── OcrService.java             # OCR 识别业务（RapidOCR + Tesseract）
└── util/
    ├── DocxUtils.java               # Word 文档工具（读写、翻译、水印清理、版式还原）
    └── PdfUtils.java                # PDF 工具

src/main/resources/
├── application.yml                  # 应用配置
├── schema.sql                       # 数据库初始化脚本
├── static/                          # 静态资源（CSS/JS）
└── templates/                       # Thymeleaf 页面模板
    ├── index.html
    ├── translate.html
    ├── pdf-convert.html
    └── ocr.html

build-tools/
└── HashAssets.java                  # 构建时为静态资源生成内容哈希，解决浏览器缓存问题
```

## 扩展说明

### 接入翻译 API

当前默认使用 Google Translate（`translate.googleapis.com`），通过 `application.yml` 中的 `translate.api-url` 配置。

可替换为其他翻译服务，修改 `TranslateService.java` 中的 `callTranslateApi` 方法即可：
- 百度翻译 API
- DeepL API
- 有道翻译 API

### 翻译水印清理

翻译完成后会自动清理文档中的评估警告水印（如 Spire 评估版生成的红色水印文字），可通过 `translate.remove-red-watermark` 配置项关闭。

### 数据库

默认使用 H2 嵌入式数据库，数据文件保存在 `./data/doctool.mv.db`，支持通过 `/h2-console` 在线管理。

如需切换到 MySQL，修改 `application.yml` 中的数据源配置即可。

## 打包部署

项目默认打包为 WAR 格式，可部署到外部 Tomcat 10+ 容器。如需改为 JAR 打包，将 `pom.xml` 中的 `<packaging>war</packaging>` 改为 `<packaging>jar</packaging>` 并移除 Tomcat provided 依赖。

支持 `dev` 和 `prod` 两个 Maven Profile，通过 `-Pprod` 激活生产环境配置。
