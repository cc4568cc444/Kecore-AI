# Qwen3-Reranker 8010 服务

该服务为项目现有的金融问答和论文问答提供兼容的 `POST /v1/rerank` 接口。默认仅监听本机 `127.0.0.1:8010`。

## 首次安装

请使用运行项目评测脚本时的同一个 Python：

```powershell
cd "D:\Kecore AI\src\main\resources\reranker"
python -m pip install -r requirements.txt
```

PyTorch/CUDA 应单独按本机环境安装；若 `python -c "import torch; print(torch.cuda.is_available())"` 输出 `True`，服务会自动使用 NVIDIA GPU。

## 启动

```powershell
cd "D:\Kecore AI\src\main\resources\reranker"
python qwen3_reranker_server.py --host 127.0.0.1 --port 8010 --device auto --max-length 1024 --batch-size 2
```

第一次启动会从 Hugging Face 下载 `Qwen/Qwen3-Reranker-0.6B`，下载和模型加载完成后才会开始监听 8010。不要关闭这个 PowerShell 窗口。

如果模型已经下载到本地，可改用：

```powershell
python qwen3_reranker_server.py --model-path "D:\models\Qwen3-Reranker-0.6B" --port 8010
```

## 自检

另开一个 PowerShell：

```powershell
Invoke-RestMethod http://127.0.0.1:8010/health

$body = @{
  model = "Qwen3-Reranker-0.6B"
  query = "Which multilingual approaches do they compare with?"
  documents = @(
    "The paper compares multilingual pretraining and translate-train baselines."
    "The weather is sunny today."
  )
  top_n = 2
  return_documents = $false
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri http://127.0.0.1:8010/v1/rerank `
  -ContentType "application/json" `
  -Body $body
```

论文评测的 `hybrid_rerank` 响应应显示 `rerankerApplied: true`，完整报告中的 `reranker_applied_rate` 应为 `1.0`。

服务只计算最后一个 yes/no token 的 logits；论文评测默认将 Hybrid 候选控制为 20 个，再选出 Top-8，以降低本地 4GB 显卡的重排延迟。

当前 GTX 1650 建议从 `--max-length 1024 --batch-size 2` 开始；若出现 CUDA 显存不足，再改为 `--batch-size 1`。该设置会截断过长候选段落，运行器会通过 `/health` 把实际长度和 batch 写入正式报告。
