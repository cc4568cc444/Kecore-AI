# Multi-Doc-2025 全量数据与索引管线

该工具把 S1-S5 的 `train/val/test` 元数据和原始 SEC 10-K 文档准备到
`datasets/multi-doc-2025`，并构建后端已配置使用的统一表
`multidoc_full_chunks`。数据目录已被 `.gitignore` 排除，不会把约 1 GiB
的原始财报提交到仓库。

## 1. 只准备元数据和文档清单

```powershell
python tools/multidoc/multidoc_pipeline.py prepare
```

这一步下载三个 QA split，读取官方 179 份文档清单，并生成 S1-S5 的问题、
文档选择文件；不会下载大体积 HTML。

## 2. 下载评测需要的财报

先用跨文档任务做小规模验证：

```powershell
python tools/multidoc/multidoc_pipeline.py prepare --splits test --subsets S3,S4,S5 --download-docs --limit-docs 5
python tools/multidoc/multidoc_pipeline.py verify
```

准备完整语料：

```powershell
python tools/multidoc/multidoc_pipeline.py prepare --all-docs --download-docs
```

下载具有缓存和文件大小校验；中断后重新执行即可。

## 3. 构建统一 pgvector 索引

确保 PostgreSQL/pgvector 与 embedding 服务已启动，然后先索引少量文档：

```powershell
python tools/multidoc/multidoc_pipeline.py index --splits test --subsets S3,S4,S5 --limit-docs 2 --dry-run
python tools/multidoc/multidoc_pipeline.py index --splits test --subsets S3,S4,S5 --limit-docs 2
```

完整索引：

```powershell
python tools/multidoc/multidoc_pipeline.py index --all-docs --rebuild
```

索引同时保存 `company`、`year`、`subset(s)`、`split(s)`、`chunk_type`、
`is_cross_doc`、`is_cross_year`、`is_hybrid_modal` 和表格期间等 metadata，
供 Java 层的 Entity-Temporal 子任务执行 metadata filtering。
