# Karaf Metrics Dashboard

Un bundle OSGi pour Apache Karaf qui fournit un **tableau de bord web temps réel** des métriques système et JVM, enrichi de graphiques, d’un système d’alertes configurables et de notifications par e‑mail.

- 🖥️ **URL** : http://[host]:[port]/metriques/dashboard 

![Aperçu du dashboard](screenshot.png)

## 🚀 Fonctionnalités

- **Métriques en temps réel** : CPU, mémoire, disque, réseau, threads, garbage collector, classes, uptime JVM.
- **Métriques OSGi** : liste des bundles avec pagination, nombre de services, configurations.
- **Interface moderne** : design sombre, responsive, graphiques interactifs (Chart.js), barres de progression.
- **Alertes intelligentes** :
  - Seuils configurables par CPU / mémoire / disque.
  - Cooldown pour éviter la répétition de notifications.
  - Rapports détaillés (compact ou complet) écrits dans un fichier.
- **Notifications par e‑mail** :
  - Envoi via **API Mailjet** (pas de dépendance JavaMail lourde).
  - Templates HTML personnalisables (alertes et résolution).
  - E‑mails de début et fin d’alerte (configurable).
- **Configuration à chaud** : tout se paramètre via un fichier `.cfg` dans `etc/` (ConfigAdmin), sans redémarrer le bundle.
- **Données exposées en SSE** : facile à consommer par d’autres outils.

## 📦 Prérequis

- Apache Karaf 4.3 ou 4.4 (avec le Whiteboard HTTP activé).
- Connexion Internet pour le CDN Chart.js (ou fichier local).
- Pour les emails : un compte Mailjet (ou autre service SMTP) avec une clé API.
- Copier le model de fichier com.hbj.karaf.metrics.alert.cfg dans le repertoir /etc de Karaf 

## 🖥️ Utilisation du dashboard
- **Vue globale** : cartes CPU, mémoire, disque, threads, classes, uptime.
- **Graphiques** : historique CPU et mémoire, répartition des bundles par état (doughnut).
- **Liste des bundles** : paginée avec recherche.
- **Détails OSGi** : nombre de services, configurations.	

## 🛠️ Installation

1. **Cloner le dépôt** :
   ```bash
   git clone https://github.com/ton-utilisateur/karaf-metriques-bundle.git
   cd karaf-metriques-bundle
   mvn clean install
   cp target/karaf-metriques-bundle-4.2.0.jar <repo karaf>/deploy
   
   