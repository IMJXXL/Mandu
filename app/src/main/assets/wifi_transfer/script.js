document.addEventListener('DOMContentLoaded', () => {
    const dropZone = document.getElementById('drop-zone');
    const fileInput = document.getElementById('file-input');
    const folderInput = document.getElementById('folder-input');
    const dropText = document.getElementById('drop-text');
    const filesTableBody = document.querySelector('#files-table tbody');
    const uploadProgress = document.getElementById('upload-progress-container');
    const uploadSummary = document.getElementById('upload-summary');
    const summaryText = document.getElementById('summary-text');
    const clearCompletedBtn = document.getElementById('clear-completed');
    const noFilesMsg = document.getElementById('no-files');
    const refreshBtn = document.getElementById('refresh-btn');

    const warningBanner = document.getElementById('warning-banner');
    const dismissWarning = document.getElementById('dismiss-warning');
    const modeTabs = document.querySelectorAll('.mode-tab');
    const confirmModal = document.getElementById('confirm-modal');
    const confirmMessage = document.getElementById('confirm-message');
    const confirmYes = document.getElementById('confirm-yes');
    const confirmNo = document.getElementById('confirm-no');
    const dragOverlay = document.getElementById('drag-overlay');
    const jinBadge = document.getElementById('jin-badge');
    const donationModal = document.getElementById('donation-modal');
    const donationClose = document.getElementById('donation-close');

    let uploadMode = 'file';
    let dragCounter = 0;

    const uploadQueue = [];
    let activeUploads = 0;
    const MAX_CONCURRENT = 3;
    let totalUploaded = 0;
    let totalFailed = 0;
    let totalQueued = 0;

    jinBadge.addEventListener('click', () => donationModal.classList.add('active'));
    donationClose.addEventListener('click', () => donationModal.classList.remove('active'));
    donationModal.addEventListener('click', (e) => { if (e.target === donationModal) donationModal.classList.remove('active'); });

    if (localStorage.getItem('dismissWarning') === 'true') warningBanner.style.display = 'none';
    dismissWarning.addEventListener('click', () => {
        warningBanner.style.display = 'none';
        localStorage.setItem('dismissWarning', 'true');
    });

    modeTabs.forEach(tab => {
        tab.addEventListener('click', () => {
            modeTabs.forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            uploadMode = tab.dataset.mode;
            dropText.textContent = uploadMode === 'file'
                ? '拖放文件到此处，或点击选择'
                : '点击选择文件夹（保留目录结构）';
        });
    });

    async function fetchFiles() {
        try {
            const res = await fetch('/api/files');
            if (!res.ok) { filesTableBody.innerHTML = `<tr><td colspan="4" class="err">加载失败</td></tr>`; return; }
            const data = await res.json();
            renderFiles(data.files);
        } catch (e) {
            filesTableBody.innerHTML = `<tr><td colspan="4" class="err">无法连接服务器</td></tr>`;
        }
    }

    function renderFiles(files) {
        filesTableBody.innerHTML = '';
        if (!files || files.length === 0) { noFilesMsg.style.display = 'flex'; return; }
        noFilesMsg.style.display = 'none';
        files.forEach(file => {
            const row = filesTableBody.insertRow();
            row.dataset.name = file.name;
            const nameCell = row.insertCell(); nameCell.textContent = file.name; nameCell.dataset.label = '名称';
            const sizeCell = row.insertCell(); sizeCell.textContent = file.formattedSize || formatBytes(file.size); sizeCell.dataset.label = '大小';
            const timeCell = row.insertCell(); timeCell.textContent = file.lastModified; timeCell.dataset.label = '时间';
            const actCell = row.insertCell(); actCell.dataset.label = '操作'; actCell.style.textAlign = 'right';
            const dl = document.createElement('a');
            dl.href = '/api/download/' + encodeURIComponent(file.name);
            dl.setAttribute('download', file.name);
            dl.className = 'icon-btn'; dl.title = '下载';
            dl.textContent = '⬇';
            const del = document.createElement('button');
            del.className = 'icon-btn btn-del'; del.title = '删除'; del.textContent = '🗑';
            del.onclick = (e) => { e.stopPropagation(); confirmDelete(file.name); };
            actCell.appendChild(dl); actCell.appendChild(del);
        });
    }

    function formatBytes(b, d = 1) {
        if (b <= 0) return '0 B';
        const k = 1024, u = ['B','KB','MB','GB','TB'];
        let i = Math.floor(Math.log(b) / Math.log(k));
        return (b / Math.pow(k, i)).toFixed(d) + ' ' + u[i];
    }

    let confirmCb = null;
    function showConfirm(msg, cb) {
        confirmMessage.textContent = msg; confirmCb = cb;
        confirmModal.classList.add('active');
    }
    function hideConfirm() { confirmModal.classList.remove('active'); confirmCb = null; }
    confirmYes.onclick = () => { if (confirmCb) confirmCb(); hideConfirm(); };
    confirmNo.onclick = () => hideConfirm();
    confirmModal.addEventListener('click', (e) => { if (e.target === confirmModal) hideConfirm(); });

    function confirmDelete(name) {
        showConfirm(`删除 "${name}"？`, () => deleteFile(name));
    }

    async function deleteFile(name) {
        try {
            const res = await fetch('/api/delete', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({filename: name}) });
            if (res.ok) {
                for (const row of filesTableBody.rows) {
                    if (row.dataset.name === name) { row.remove(); break; }
                }
                if (filesTableBody.children.length === 0) noFilesMsg.style.display = 'flex';
            }
        } catch (e) { console.error('delete failed', e); }
    }

    dropZone.addEventListener('click', (e) => {
        if (e.target === fileInput || e.target === folderInput) return;
        e.stopPropagation();
        if (uploadMode === 'folder') folderInput.click();
        else fileInput.click();
    });

    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault(); dropZone.classList.remove('dragover');
        const files = e.dataTransfer?.files;
        if (files && files.length > 0) handleFiles(files);
    });

    window.addEventListener('dragenter', (e) => {
        if (!e.dataTransfer || !e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        dragCounter++;
        dragOverlay.classList.add('active');
    });
    window.addEventListener('dragover', (e) => {
        if (!e.dataTransfer || !e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
    });
    window.addEventListener('dragleave', (e) => {
        if (!e.dataTransfer || !e.dataTransfer.types.includes('Files')) return;
        e.preventDefault();
        dragCounter--;
        if (dragCounter <= 0) { dragCounter = 0; dragOverlay.classList.remove('active'); }
    });
    window.addEventListener('drop', (e) => {
        e.preventDefault();
        dragCounter = 0;
        dragOverlay.classList.remove('active');
    });

    fileInput.addEventListener('change', (e) => { if (e.target.files.length > 0) handleFiles(e.target.files); e.target.value = ''; });
    folderInput.addEventListener('change', (e) => { if (e.target.files.length > 0) handleFiles(e.target.files); e.target.value = ''; });

    function handleFiles(files) {
        const arr = Array.from(files);
        let imgCount = 0;
        arr.forEach(f => {
            const ext = f.name.split('.').pop().toLowerCase();
            if (['jpg','jpeg','png','gif','webp','bmp'].includes(ext)) imgCount++;
        });
        if (imgCount > 20 && !localStorage.getItem('dismissWarning')) {
            showConfirm(`检测到 ${imgCount} 张散图，可能影响性能。建议压缩后传输。仍要继续？`, () => {
                arr.forEach(f => enqueueUpload(f));
            });
        } else {
            arr.forEach(f => enqueueUpload(f));
        }
    }

    function enqueueUpload(file) {
        totalQueued++;
        updateSummary();
        uploadQueue.push(file);
        processQueue();
    }

    function processQueue() {
        while (activeUploads < MAX_CONCURRENT && uploadQueue.length > 0) {
            const file = uploadQueue.shift();
            activeUploads++;
            uploadFile(file);
        }
    }

    function updateSummary() {
        const pending = uploadQueue.length;
        const active = activeUploads;
        if (totalQueued === 0) { uploadSummary.style.display = 'none'; return; }
        uploadSummary.style.display = 'flex';
        const parts = [];
        if (active > 0) parts.push(`${active} 上传中`);
        if (pending > 0) parts.push(`${pending} 排队`);
        if (totalUploaded > 0) parts.push(`${totalUploaded} 成功`);
        if (totalFailed > 0) parts.push(`${totalFailed} 失败`);
        summaryText.textContent = parts.join(' · ') || '全部完成';
    }

    function uploadFile(file) {
        const formData = new FormData();
        const path = file.webkitRelativePath || file.name;
        formData.append('file', file, path);
        const xhr = new XMLHttpRequest();
        const item = document.createElement('div');
        item.className = 'progress-item';
        const label = document.createElement('div');
        label.className = 'progress-label';
        label.textContent = `${path} (${formatBytes(file.size)})`;
        const bar = document.createElement('div');
        bar.className = 'progress-bar';
        const fill = document.createElement('div');
        fill.className = 'progress-fill';
        const status = document.createElement('span');
        status.className = 'progress-status';
        bar.appendChild(fill); item.appendChild(label); item.appendChild(bar); item.appendChild(status);
        uploadProgress.prepend(item);

        xhr.upload.addEventListener('progress', (e) => {
            if (e.lengthComputable) {
                const pct = (e.loaded / e.total) * 100;
                fill.style.width = pct.toFixed(1) + '%';
                status.textContent = pct.toFixed(0) + '%';
            }
        });
        xhr.addEventListener('load', () => {
            activeUploads--;
            if (xhr.status >= 200 && xhr.status < 300) {
                totalUploaded++;
                fill.style.backgroundColor = 'var(--success)';
                status.textContent = '✓';
                setTimeout(() => item.remove(), 2000);
                fetchFiles();
            } else {
                totalFailed++;
                fill.style.backgroundColor = 'var(--error)';
                status.textContent = '失败';
            }
            updateSummary();
            processQueue();
        });
        xhr.addEventListener('error', () => {
            activeUploads--;
            totalFailed++;
            fill.style.backgroundColor = 'var(--error)';
            status.textContent = '网络错误';
            updateSummary();
            processQueue();
        });
        xhr.open('POST', '/api/upload', true);
        xhr.send(formData);
    }

    clearCompletedBtn.addEventListener('click', () => {
        uploadProgress.querySelectorAll('.progress-item').forEach(item => {
            const status = item.querySelector('.progress-status');
            if (status && (status.textContent === '✓' || status.textContent === '失败' || status.textContent === '网络错误')) {
                item.remove();
            }
        });
        if (uploadProgress.children.length === 0) {
            totalQueued = 0; totalUploaded = 0; totalFailed = 0;
            uploadSummary.style.display = 'none';
        }
    });

    refreshBtn.addEventListener('click', fetchFiles);
    fetchFiles();
});