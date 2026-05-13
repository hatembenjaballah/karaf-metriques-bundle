(function() {
    'use strict';

    // URL absolue pour le flux SSE
    const streamUrl = '/dashboard/stream';

    // Formatage
    function formatBytes(bytes) {
        if (!bytes || bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    function formatTime(ts) {
        return new Date(ts).toLocaleTimeString('fr-FR', {
            hour: '2-digit', minute: '2-digit', second: '2-digit'
        });
    }

    // Mise à jour du dashboard
    function updateDashboard(data) {
        // CPU
        const cpu = parseFloat(data.cpuUsage);
        document.getElementById('cpuValue').textContent = isNaN(cpu) ? 'N/A' : cpu.toFixed(1);
        document.getElementById('cpuBar').style.width = Math.max(0, Math.min(100, cpu)) + '%';

        // Mémoire
        const mem = parseFloat(data.memoryUsage);
        document.getElementById('memValue').textContent = mem.toFixed(1);
        document.getElementById('memBar').style.width = mem + '%';
        document.getElementById('memDetails').textContent =
            formatBytes(data.usedMemory) + ' / ' + formatBytes(data.totalMemory);

        // Disque
        const disk = parseFloat(data.diskUsage);
        document.getElementById('diskValue').textContent = disk.toFixed(1);
        document.getElementById('diskBar').style.width = disk + '%';
        document.getElementById('diskDetails').textContent =
            formatBytes(data.usedDisk) + ' / ' + formatBytes(data.totalDisk);

        // Tableau détaillé
        const tableBody = document.querySelector('#metricsTable tbody');
        const rows = [
            ['CPU', cpu.toFixed(1)+'%', cpu.toFixed(1), '100%', cpu.toFixed(1)+'%'],
            ['Mémoire', formatBytes(data.usedMemory)+' / '+formatBytes(data.totalMemory), mem.toFixed(1), formatBytes(data.totalMemory), formatBytes(data.usedMemory)],
            ['Disque', formatBytes(data.usedDisk)+' / '+formatBytes(data.totalDisk), disk.toFixed(1), formatBytes(data.totalDisk), formatBytes(data.usedDisk)]
        ];
        tableBody.innerHTML = rows.map(r =>
            `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`
        ).join('');

        // Interfaces réseau
        const netBody = document.getElementById('networkTableBody');
        let netHtml = '';
        (data.networkInterfaces || []).forEach(iface => {
            const statusClass = iface.status === 'UP' ? 'badge-up' : 'badge-down';
            netHtml += `<tr>
                <td>${iface.displayName || iface.name}</td>
                <td><span class="${statusClass}">● ${iface.status}</span></td>
                <td>${(iface.addresses || []).join(', ') || '—'}</td>
                <td>${iface.mac || '—'}</td>
            </tr>`;
        });
        netBody.innerHTML = netHtml;

        // Horodatage
        document.getElementById('lastUpdate').textContent = 'Dernière mise à jour : ' + formatTime(data.timestamp);
    }

    // Connexion SSE (reconnexion automatique native)
    const eventSource = new EventSource(streamUrl);
    eventSource.onmessage = function(event) {
        try {
            const data = JSON.parse(event.data);
            updateDashboard(data);
        } catch (e) {
            console.error('Erreur JSON SSE:', e);
        }
    };
    eventSource.onerror = function() {
        console.warn('SSE error, tentative de reconnexion automatique...');
    };
})();