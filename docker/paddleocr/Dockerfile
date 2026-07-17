# PaddleOCR 开源 OCR serving 镜像（官方 PaddleX basic serving 方案）
# 官方无预构建 basic-serving 镜像，故基于 python 官方镜像自建：pip 装 paddlepaddle + paddleocr，
# 再装 serving 插件（fastapi/uvicorn），启动官方 `paddlex --serve --pipeline OCR`，暴露 REST 端点 POST /ocr。
#
# 契约（官方 PaddleX 3.x OCR pipeline）：
#   POST /ocr  body={"file":"<base64或URL>","fileType":1}
#   resp={"errorCode":0,"result":{"ocrResults":[{"prunedResult":{"rec_texts":[...],"rec_scores":[...]}}]}}
#
# 版本固定：paddlepaddle/paddleocr 3.x 跨小版本组合有已知不兼容（见 PaddlePaddle/Paddle#76111）。
# 实测锁定 paddleocr==3.0.0（OCR pipeline 默认 PP-OCRv5）+ paddlepaddle==3.0.0：这是被多方验证的稳定组合；
# 若放开到 3.1.x 会拉取过新的 PP-OCRv6 模型，与 paddle 3.0.0 推理运行时不匹配而报 "strides is not right" 建管失败。
FROM python:3.10-slim

ENV PIP_NO_CACHE_DIR=1 \
    DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1

# paddle 运行期依赖的系统库（OpenMP / glib / OpenCV 依赖），curl 供 healthcheck 用
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libglib2.0-0 libgl1 curl \
    && rm -rf /var/lib/apt/lists/*

# 先装推理框架再装 OCR 与 serving 插件；-i 走清华镜像加速国内拉取
RUN pip install --index-url https://pypi.tuna.tsinghua.edu.cn/simple paddlepaddle==3.0.0 \
    && pip install --index-url https://pypi.tuna.tsinghua.edu.cn/simple paddleocr==3.0.0 \
    && paddlex --install serving -y

EXPOSE 8080

# --host 0.0.0.0 让容器外可访问；模型首次请求时自动下载（缓存进挂载卷，见 compose）
CMD ["paddlex", "--serve", "--pipeline", "OCR", "--host", "0.0.0.0", "--port", "8080"]
