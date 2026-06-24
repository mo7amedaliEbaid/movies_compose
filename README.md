# TMDb Android Compose Learning Client

A modern, offline-first Android application built with **Jetpack Compose** using the free TMDb API. This project was developed as a hands-on learning tool to master Clean Architecture, MVVM, Dependency Injection, Room Database caching, and Jetpack Compose performance optimizations, mirroring premium iOS app aesthetics.

---

## 📱 Features

- **Trending Movies Grid**: A modern 2-column grid displaying the latest trending movies.
- **Aesthetic Shimmer Loading**: A custom, theme-adaptive shimmering skeleton screen that animates placeholders during initial loads and searches, matching premium iOS styles.
- **Debounced Search**: Responsive search input featuring a `500ms` debounce window using Kotlin Flows to optimize network bandwidth and prevent server rate-limits.
- **Cache-First details Screen**: Displays backdrop headers, technical metadata badges (Rating, Year, and dynamic Runtime), horizontally scrollable genre chips, and movie synopsis. Loads instantly from local cache first, then fetches complete secondary details asynchronously.
- **Offline Cache (SSOT)**: Local SQLite caching via Room Database acting as the Single Source of Truth, enabling fully functional offline browsing.

---

## 🛠 Tech Stack & Architecture

- **UI Framework**: Jetpack Compose (Material 3)
- **Architecture**: Clean Architecture (Presentation, Domain, Data layers) + MVVM
- **Dependency Injection**: Dagger Hilt
- **Local Database**: Room Cache (with Coroutine database transactions)
- **Networking**: Retrofit 2.11 + OkHttp 4
- **Serialization**: Kotlinx Serialization (Compile-time safe, reflection-free JSON parser)
- **Image Loading**: Coil AsyncImage
- **Asynchronous Flow**: Kotlin Coroutines & Flow (StateFlow, SharedFlow, debounce, distinctUntilChanged)

---

## 🗺 Interactive Study Guides & Interview Prep

As part of the development process, a detailed curriculum of study guides and technical interview preparation sheets was compiled in the `/study` directory:

| Module | Core Concepts & Design Docs | Technical Interview Prep |
|---|---|---|
| **01. Setup & Networking** | [Networking & JSON Serialization](study/01_networking_serialization.md) | [Networking & Serialization Q&A](study/01_networking_interview.md) |
| **02. Dependency Injection** | [Architecture & Hilt DI](study/02_di_architecture.md) | [Clean Architecture & DI Q&A](study/02_di_architecture_interview.md) |
| **03. Room Caching** | [Room DB & Flow Streams](study/03_room_caching.md) | [Caching & Reactive Flows Q&A](study/03_caching_flow_interview.md) |
| **04. Compose UI State** | [Compose State & Recomposition](study/04_compose_state.md) | [Compose State & Layouts Q&A](study/04_compose_state_interview.md) |
| **05. Navigation & Search** | [Navigation Compose & Debouncing](study/05_navigation.md) | [Navigation & SavedStateHandle Q&A](study/05_navigation_interview.md) |

---

## 🔑 Secure API Key Setup

The TMDb API Read Access Token (v4 auth JWT) is kept completely secure and is **never** checked into Git. To configure it:

1. Register for a free account at [The Movie Database (TMDb)](https://www.themoviedb.org/).
2. Generate an **API Read Access Token (v4 auth)** in your profile settings.
3. Open your local `local.properties` file at the root of the project and add the following line:
   ```properties
   tmdb.api.key=YOUR_BEARER_TOKEN_HERE
   ```
4. Build and run. The token is dynamically injected into `BuildConfig.TMDB_API_KEY` at compile time and is injected into headers by OkHttp.

---

## 🚀 Building & Running

1. Clone this repository to your local machine.
2. Open the project in **Android Studio (Ladybug or newer)**.
3. Add your TMDb bearer token to `local.properties` (see step above).
4. Sync Gradle files.
5. Click **Run** or run the compile tasks in terminal:
   ```bash
   ./gradlew assembleDebug
   ```
