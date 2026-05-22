// =====================================================
// Initialisation sécurisée des graphiques Chart.js
// =====================================================
const historyCanvas = document.getElementById('historyChart');
let historyChart = null;
if (historyCanvas) {
    const ctxHistory = historyCanvas.getContext('2d');
    historyChart = new Chart(ctxHistory, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'CPU %',
                    data: [],
                    borderColor: '#4caf50',
                    backgroundColor: 'rgba(76,175,80,0.1)',
                    tension: 0.3,
                    fill: true,
                    yAxisID: 'y'
                },
                {
                    label: 'Mémoire %',
                    data: [],
                    borderColor: '#ff9800',
                    backgroundColor: 'rgba(255,152,0,0.1)',
                    tension: 0.3,
                    fill: true,
                    yAxisID: 'y'
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                y: {
                    beginAtZero: true,
                    max: 100,
                    ticks: { color: '#8a9bb5' },
                    grid: { color: '#2d3b4f' }
                },
                x: {
                    ticks: { color: '#8a9bb5', maxRotation: 0 },
                    grid: { display: false }
                }
            },
            plugins: {
                legend: { labels: { color: '#e0e0e0' } }
            }
        }
    });
} else {
    console.error('Canvas historyChart introuvable');
}

const bundleCanvas = document.getElementById('bundleChart');
let bundleChart = null;
if (bundleCanvas) {
    const ctxBundle = bundleCanvas.getContext('2d');
    bundleChart = new Chart(ctxBundle, {
        type: 'doughnut',
        data: {
            labels: ['ACTIVE', 'INSTALLED', 'RESOLVED', 'STARTING', 'STOPPING', 'UNINSTALLED'],
            datasets: [{
                data: [],
                backgroundColor: ['#4caf50', '#ff9800', '#2196f3', '#9c27b0', '#f44336', '#607d8b']
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: { color: '#e0e0e0' }
                }
            }
        }
    });
} else {
    console.error('Canvas bundleChart introuvable');
}

// =====================================================
// Historique CPU / Mémoire
// =====================================================
const maxHistory = 20;
let cpuHistory = [];
let memHistory = [];

// =====================================================
// Tableau des bundles – pagination, tri, filtre
// =====================================================
let bundleMetrics = [];
let filteredBundles = [];
let currentPage = 1;
const rowsPerPage = 10;
let currentSortColumn = 'id';
let sortDirection = 'asc';

const filterInput = document.getElementById('bundleFilter');
const resetFilterBtn = document.getElementById('resetFilterBtn');
const tableBody = document.querySelector('#bundle-table tbody');
const prevBtn = document.getElementById('prevPageBtn');
const nextBtn = document.getElementById('nextPageBtn');
const pageInfo = document.getElementById('pageInfo');

function updateBundleTable(data) {
    if (data.bundleMetrics && data.bundleMetrics.length > 0) {
        bundleMetrics = data.bundleMetrics.map(b => ({
            ...b,
            diskMb: (b.diskSize / (1024 * 1024)).toFixed(2),
            estMemMb: (b.estimatedMemory / (1024 * 1024)).toFixed(2)
        }));
    } else {
        bundleMetrics = [];
    }
    applyFilterAndSort(true);
}

function applyFilter() {
    const term = filterInput.value.toLowerCase().trim();
    if (term === '') {
        filteredBundles = [...bundleMetrics];
    } else {
        filteredBundles = bundleMetrics.filter(b =>
            b.symbolicName.toLowerCase().includes(term) ||
            String(b.id).includes(term)
        );
    }
}

function sortBundles() {
    filteredBundles.sort((a, b) => {
        let valA = a[currentSortColumn];
        let valB = b[currentSortColumn];
        if (['id', 'diskSize', 'activeThreads', 'classCount', 'cpuTime', 'estimatedMemory'].includes(currentSortColumn)) {
            valA = Number(valA);
            valB = Number(valB);
            return sortDirection === 'asc' ? valA - valB : valB - valA;
        }
        valA = String(valA).toLowerCase();
        valB = String(valB).toLowerCase();
        if (sortDirection === 'asc') return valA.localeCompare(valB);
        else return valB.localeCompare(valA);
    });
}

function renderPage() {
    const totalPages = Math.ceil(filteredBundles.length / rowsPerPage);
    if (currentPage > totalPages) currentPage = totalPages || 1;
    const start = (currentPage - 1) * rowsPerPage;
    const pageItems = filteredBundles.slice(start, start + rowsPerPage);

    tableBody.innerHTML = '';
    pageItems.forEach(b => {
        const row = `
            <tr>
                <td>${b.id}</td>
                <td>${b.symbolicName}</td>
                <td>${b.state}</td>
                <td>${b.diskMb}</td>
                <td>${b.activeThreads}</td>
                <td>${b.classCount}</td>
                <td>${b.cpuTime.toFixed(0)}</td>
                <td>${b.estMemMb}</td>
            </tr>`;
        tableBody.innerHTML += row;
    });

    pageInfo.textContent = `Page ${currentPage} / ${totalPages}`;
    prevBtn.disabled = currentPage <= 1;
    nextBtn.disabled = currentPage >= totalPages;
}

prevBtn.addEventListener('click', () => {
    if (currentPage > 1) { currentPage--; renderPage(); }
});
nextBtn.addEventListener('click', () => {
    const totalPages = Math.ceil(filteredBundles.length / rowsPerPage);
    if (currentPage < totalPages) { currentPage++; renderPage(); }
});

filterInput.addEventListener('input', () => applyFilterAndSort(true));
resetFilterBtn.addEventListener('click', () => {
    filterInput.value = '';
    applyFilterAndSort(true);
});

document.querySelectorAll('#bundle-table th[data-sort]').forEach(th => {
    th.addEventListener('click', () => {
        const column = th.dataset.sort;
        if (currentSortColumn === column) {
            sortDirection = sortDirection === 'asc' ? 'desc' : 'asc';
        } else {
            currentSortColumn = column;
            sortDirection = 'asc';
        }
        document.querySelectorAll('#bundle-table th').forEach(h => h.textContent = h.textContent.replace(' ↕', '').replace(' ↑', '').replace(' ↓', ''));
        th.textContent += sortDirection === 'asc' ? ' ↑' : ' ↓';
        applyFilterAndSort(false);
    });
});

function applyFilterAndSort(resetPage = true) {
    if (resetPage) currentPage = 1;
    applyFilter();
    sortBundles();
    renderPage();
}

// =====================================================
// Connexion SSE et mise à jour globale de l'interface
// =====================================================
const source = new EventSource('/metriques/dashboard/stream');
source.onmessage = function (event) {
    try {
        const data = JSON.parse(event.data);
        updateUI(data);
    } catch (e) {
        console.error('Erreur parsing JSON', e);
    }
};
source.onerror = () => console.error('Erreur SSE');

function updateUI(data) {
    // CPU
    const cpu = data.cpu || 0;
    document.getElementById('cpu').textContent = cpu.toFixed(1) + '%';
    document.getElementById('cpu-bar').style.width = cpu.toFixed(1) + '%';

    // Mémoire
    if (data.memory) {
        const memUsed = data.memory.used;
        const memMax = data.memory.max;
        const memPercent = data.memory.percent || 0;
        document.getElementById('mem-used').textContent = memUsed + ' Mo';
        document.getElementById('mem-bar').style.width = memPercent.toFixed(1) + '%';
        document.getElementById('mem-detail').textContent = `Max: ${memMax} Mo (${memPercent.toFixed(1)}%)`;
    }

    // Disque
    if (data.disk) {
        const diskUsed = data.disk.used;
        const diskTotal = data.disk.total;
        const diskPercent = data.disk.percent || 0;
        document.getElementById('disk-used').textContent = diskUsed + ' Go';
        document.getElementById('disk-bar').style.width = diskPercent.toFixed(1) + '%';
        document.getElementById('disk-detail').textContent = `Total: ${diskTotal} Go (${diskPercent.toFixed(1)}%)`;
    }

    // JVM
    if (data.jvm) {
        document.getElementById('threads-current').textContent = data.jvm.threadCount;
        document.getElementById('threads-peak').textContent = data.jvm.peakThreadCount;

        const uptimeSec = data.jvm.uptime / 1000;
        const days = Math.floor(uptimeSec / 86400);
        const hours = Math.floor((uptimeSec % 86400) / 3600);
        const mins = Math.floor((uptimeSec % 3600) / 60);
        document.getElementById('jvm-uptime').textContent = `${days}j ${hours}h ${mins}m`;

        document.getElementById('classes-loaded').textContent = data.jvm.classes.loadedCount;
        document.getElementById('classes-unloaded').textContent = data.jvm.classes.unloadedCount;

        let gcHtml = '';
        if (data.jvm.gc) {
            gcHtml += `<p>Total collectes : ${data.jvm.gc.totalCollectionCount}</p>`;
            gcHtml += `<p>Temps total : ${data.jvm.gc.totalCollectionTime} ms</p>`;
            data.jvm.gc.collectors.forEach(gc => {
                gcHtml += `<p><b>${gc.name}</b>: ${gc.collectionCount} fois, ${gc.collectionTime} ms</p>`;
            });
        }
        document.getElementById('gc-info').innerHTML = gcHtml;
    }

    // Historique
    cpuHistory.push(cpu);
    memHistory.push(data.memory ? data.memory.percent : 0);
    if (cpuHistory.length > maxHistory) cpuHistory.shift();
    if (memHistory.length > maxHistory) memHistory.shift();
    if (historyChart) {
        historyChart.data.labels = cpuHistory.map((_, i) => i);
        historyChart.data.datasets[0].data = cpuHistory;
        historyChart.data.datasets[1].data = memHistory;
        historyChart.update();
    }

    // Doughnut
    if (bundleChart && data.osgi && data.osgi.bundles) {
        const byState = data.osgi.bundles.byState;
        const states = ['ACTIVE', 'INSTALLED', 'RESOLVED', 'STARTING', 'STOPPING', 'UNINSTALLED'];
        const counts = states.map(s => byState[s] || 0);
        bundleChart.data.datasets[0].data = counts;
        bundleChart.update();
    }

    // Services
    if (data.osgi) {
        document.getElementById('service-count').textContent = data.osgi.serviceCount || 0;
    }

    // Configurations
    if (data.osgi && data.osgi.configurations) {
        const configs = data.osgi.configurations;
        let configHtml = '';
        configs.forEach(c => {
            configHtml += `<li style="padding:4px 0; border-bottom:1px solid #2d3b4f;">${c.pid} ${c.factoryPid ? '<span style="color:#8a9bb5;">(factory)</span>' : ''}</li>`;
        });
        document.getElementById('config-list').innerHTML = configHtml;
    }

    // Tableau des bundles avec métriques
    updateBundleTable(data);
}