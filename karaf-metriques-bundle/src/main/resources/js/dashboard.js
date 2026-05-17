// Initialisation des graphiques
const ctxHistory = document.getElementById('historyChart').getContext('2d');
const historyChart = new Chart(ctxHistory, {
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

const ctxBundle = document.getElementById('bundleChart').getContext('2d');
const bundleChart = new Chart(ctxBundle, {
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

// Historique limité à 20 points
const maxHistory = 20;
let cpuHistory = [];
let memHistory = [];

// Pagination bundles
const bundlesPerPage = 10;
let currentPage = 1;
let allBundles = [];

// Connexion SSE
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

    // Threads
    if (data.jvm) {
        document.getElementById('threads-current').textContent = data.jvm.threadCount;
        document.getElementById('threads-peak').textContent = data.jvm.peakThreadCount;

        // Uptime
        const uptimeSec = data.jvm.uptime / 1000;
        const days = Math.floor(uptimeSec / 86400);
        const hours = Math.floor((uptimeSec % 86400) / 3600);
        const mins = Math.floor((uptimeSec % 3600) / 60);
        document.getElementById('jvm-uptime').textContent = `${days}j ${hours}h ${mins}m`;

        // Classes
        document.getElementById('classes-loaded').textContent = data.jvm.classes.loadedCount;
        document.getElementById('classes-unloaded').textContent = data.jvm.classes.unloadedCount;

        // GC
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

    // Historique CPU / Mémoire
    cpuHistory.push(cpu);
    memHistory.push(data.memory ? data.memory.percent : 0);
    if (cpuHistory.length > maxHistory) cpuHistory.shift();
    if (memHistory.length > maxHistory) memHistory.shift();

    historyChart.data.labels = cpuHistory.map((_, i) => i);
    historyChart.data.datasets[0].data = cpuHistory;
    historyChart.data.datasets[1].data = memHistory;
    historyChart.update();

    // Bundles
    if (data.osgi && data.osgi.bundles) {
        const bundles = data.osgi.bundles;
        document.getElementById('service-count').textContent = data.osgi.serviceCount || 0;

        // Doughnut : répartition par état
        const states = ['ACTIVE', 'INSTALLED', 'RESOLVED', 'STARTING', 'STOPPING', 'UNINSTALLED'];
        const counts = states.map(s => bundles.byState[s] || 0);
        bundleChart.data.datasets[0].data = counts;
        bundleChart.update();

        // Tableau paginé
        allBundles = bundles.list || [];
        currentPage = Math.min(currentPage, Math.ceil(allBundles.length / bundlesPerPage) || 1);
        renderBundleTable();
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
}

function renderBundleTable() {
    const tbody = document.querySelector('#bundle-table tbody');
    const start = (currentPage - 1) * bundlesPerPage;
    const pageBundles = allBundles.slice(start, start + bundlesPerPage);
    tbody.innerHTML = '';
    pageBundles.forEach(b => {
        const row = `<tr>
            <td>${b.id}</td>
            <td>${b.symbolicName || ''}</td>
            <td>${b.state}</td>
        </tr>`;
        tbody.innerHTML += row;
    });

    const totalPages = Math.ceil(allBundles.length / bundlesPerPage);
    document.getElementById('pageInfo').textContent = `Page ${currentPage} / ${totalPages}`;
    document.getElementById('prevBtn').disabled = currentPage <= 1;
    document.getElementById('nextBtn').disabled = currentPage >= totalPages;
}

// Contrôles pagination
document.getElementById('prevBtn').addEventListener('click', () => {
    if (currentPage > 1) { currentPage--; renderBundleTable(); }
});
document.getElementById('nextBtn').addEventListener('click', () => {
    const totalPages = Math.ceil(allBundles.length / bundlesPerPage);
    if (currentPage < totalPages) { currentPage++; renderBundleTable(); }
});