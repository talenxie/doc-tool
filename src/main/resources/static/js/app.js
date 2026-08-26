function uploadFile(apiUrl, fileInputId, resultId, errorId, loadingId, submitBtnId) {
    const fileInput = document.getElementById(fileInputId);
    const resultDiv = document.getElementById(resultId);
    const errorDiv = document.getElementById(errorId);
    const loadingDiv = document.getElementById(loadingId);
    const submitBtn = document.getElementById(submitBtnId);

    if (!fileInput.files.length) {
        alert('请选择文件');
        return;
    }

    const formData = new FormData();
    formData.append('file', fileInput.files[0]);

    resultDiv.style.display = 'none';
    errorDiv.style.display = 'none';
    loadingDiv.style.display = 'block';
    submitBtn.disabled = true;

    fetch(apiUrl, {
        method: 'POST',
        body: formData
    })
    .then(response => handleResponse(response, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl))
    .catch(err => {
        showError('请求失败: ' + err.message, resultDiv, errorDiv, loadingDiv, submitBtn);
    });
}

function handleResponse(response, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl) {
    const contentType = response.headers.get('Content-Type') || '';
    const isFileResponse = contentType.includes('application/vnd.openxmlformats')
        || contentType.includes('application/octet-stream');

    // 明确是文件响应 → 直接下载
    if (response.ok && isFileResponse) {
        downloadFromResponse(response);
        loadingDiv.style.display = 'none';
        submitBtn.disabled = false;
        resultDiv.style.display = 'block';
        document.getElementById('resultText').textContent = '处理完成，文件已自动下载';
        hideDownloadLink();
        return;
    }

    // 尝试 JSON 解析，失败则当作文件下载（Nginx 可能丢失 Content-Type）
    response.clone().text().then(text => {
        try {
            const data = JSON.parse(text);
            handleJsonResult(data, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl);
        } catch (e) {
            // 不是 JSON → 当文件下载
            if (response.ok) {
                downloadFromResponse(response);
                loadingDiv.style.display = 'none';
                submitBtn.disabled = false;
                resultDiv.style.display = 'block';
                document.getElementById('resultText').textContent = '处理完成，文件已自动下载';
                hideDownloadLink();
            } else {
                showError('服务器返回错误', resultDiv, errorDiv, loadingDiv, submitBtn);
            }
        }
    });
}

function handleJsonResult(data, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl) {
    if (data.status === 'PROCESSING' && data.id) {
        // OCR 异步轮询
        const statusUrl = '/api/ocr/status/' + data.id;
        pollStatus(statusUrl, resultDiv, errorDiv, loadingDiv, submitBtn);
    } else if (data.status === 'SUCCESS') {
        showResult(data, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl);
    } else {
        showError(data.errorMessage || '处理失败，请重试', resultDiv, errorDiv, loadingDiv, submitBtn);
    }
}

function pollStatus(statusUrl, resultDiv, errorDiv, loadingDiv, submitBtn) {
    const poll = setInterval(() => {
        fetch(statusUrl)
            .then(response => {
                const contentType = response.headers.get('Content-Type') || '';
                const isFileResponse = contentType.includes('application/vnd.openxmlformats')
                    || contentType.includes('application/octet-stream');

                // 明确是文件响应 → 下载
                if (response.ok && isFileResponse) {
                    clearInterval(poll);
                    downloadFromResponse(response);
                    loadingDiv.style.display = 'none';
                    submitBtn.disabled = false;
                    resultDiv.style.display = 'block';
                    document.getElementById('resultText').textContent = '识别完成，文件已自动下载';
                    hideDownloadLink();
                    return;
                }

                // 尝试 JSON，失败则当文件
                response.clone().text().then(text => {
                    try {
                        const data = JSON.parse(text);
                        if (data.status === 'FAILED') {
                            clearInterval(poll);
                            showError(data.errorMessage || '处理失败', resultDiv, errorDiv, loadingDiv, submitBtn);
                        } else if (data.status === 'EXPIRED') {
                            clearInterval(poll);
                            showError('结果已过期，请重新识别', resultDiv, errorDiv, loadingDiv, submitBtn);
                        }
                        // PROCESSING 继续轮询
                    } catch (e) {
                        // 不是 JSON → OCR 完成，当文件下载
                        if (response.ok) {
                            clearInterval(poll);
                            downloadFromResponse(response);
                            loadingDiv.style.display = 'none';
                            submitBtn.disabled = false;
                            resultDiv.style.display = 'block';
                            document.getElementById('resultText').textContent = '识别完成，文件已自动下载';
                            hideDownloadLink();
                        }
                    }
                });
            })
            .catch(() => {});
    }, 2000);
}

function downloadFromResponse(response) {
    const disposition = response.headers.get('Content-Disposition') || '';
    const fileName = extractFileName(disposition);
    response.blob().then(blob => {
        downloadBlob(blob, fileName);
    });
}

function hideDownloadLink() {
    var dlLink = document.getElementById('downloadLink');
    if (dlLink) dlLink.style.display = 'none';
}

function extractFileName(disposition) {
    const match = disposition.match(/filename\*?=(?:UTF-8'')?([^;\n]*)/i);
    if (match) return decodeURIComponent(match[1].replace(/"/g, ''));
    return 'download.docx';
}

function downloadBlob(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName || 'download.docx';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

function showResult(data, resultDiv, errorDiv, loadingDiv, submitBtn, apiUrl) {
    loadingDiv.style.display = 'none';
    submitBtn.disabled = false;
    resultDiv.style.display = 'block';
    document.getElementById('resultText').textContent =
        '文件: ' + data.originalFilename + ' 处理完成';
    var dlLink = document.getElementById('downloadLink');
    if (dlLink) {
        dlLink.style.display = '';
        dlLink.href = apiUrl + '/download/' + data.resultFilename;
    }
}

function showError(message, resultDiv, errorDiv, loadingDiv, submitBtn) {
    loadingDiv.style.display = 'none';
    submitBtn.disabled = false;
    errorDiv.style.display = 'block';
    document.getElementById('errorText').textContent = message;
}
