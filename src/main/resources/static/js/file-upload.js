(function() {
    var area = document.getElementById('uploadArea');
    if (!area) return;

    var fileInput = area.querySelector('input[type="file"]');
    var fileNameEl = document.getElementById('fileName');

    function updateFileName() {
        if (fileInput.files.length > 0) {
            area.classList.add('has-file');
            if (fileNameEl) fileNameEl.textContent = fileInput.files[0].name;
        } else {
            area.classList.remove('has-file');
        }
    }

    fileInput.addEventListener('change', updateFileName);

    area.addEventListener('dragover', function(e) {
        e.preventDefault();
        area.classList.add('dragover');
    });

    area.addEventListener('dragleave', function() {
        area.classList.remove('dragover');
    });

    area.addEventListener('drop', function(e) {
        e.preventDefault();
        area.classList.remove('dragover');
        if (e.dataTransfer.files.length > 0) {
            fileInput.files = e.dataTransfer.files;
            updateFileName();
        }
    });
})();
