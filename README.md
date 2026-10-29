# DrawEngineLab

삼성노트식 필기·드로잉을 **세 가지 렌더링 방식**으로 구현하고 비교하는 안드로이드 실험 앱.

삼성 노트 앱을 역공학(REA)으로 분석해 "어떻게 만들었는가"를 파악한 뒤, 그 구조를 **클린룸**으로 재현한다. 삼성의 코드는 포함하지 않으며, 공개 기술만으로 세 구현을 만들어 필기감·성능·호환성·확대 품질을 실측 비교한다.

| 버전 | 모듈 | 한 줄 정의 |
|---|---|---|
| **Ink API** | `:feature-ink` | Google 공식 Jetpack Ink API로 구현 |
| **Canvas** | `:feature-canvas` | 순수 Compose Canvas (스마트러닝 렌더 + 삼성노트식 펜 기능 하이브리드) |
| **NDK GL** | `:feature-nativegl` | 역공학으로 본 삼성 구조를 C++/GLES2로 직접 추측 설계 |

세 버전 모두 동일 기능을 제공한다 — 펜 여러 종 · 6색 + 색 스펙트럼 · 투명도 · 굵기 연속(2~40) · 획/픽셀 지우개 · 되돌리기·다시·전체삭제 · 핀치 확대·이동 · S Pen 지원 · 텍스트 박스(키보드 입력, 굵게·기울임·밑줄·크기·색·정렬·글머리).

## 문서

- 📄 [삼성노트 역공학 분석](docs/samsung-notes-reverse-engineering.md) — 삼성 노트 드로잉 구조 분석(아키텍처·네이티브 엔진·Ghidra 디컴파일 실측)과 클로닝 가능 여부 검토.
- 📄 [드로잉 구현 3종 비교](docs/drawing-engine-comparison.md) — Ink / Canvas / NDK GL 장단점·지우개 로직·권고.

## 결론 요약

| 항목 | Ink API | Canvas | NDK GL |
|---|---|---|---|
| 필기감 | ★★★ 최상(엔진 내장) | ★★ | ★★ |
| 호환성 | △ 구형 약함 | ★ 최대 | ★ 좋음 |
| 확대 품질 | 선명(벡터) | 깨짐(래스터 베이킹) | 선명(GPU 벡터) |
| 획 많을 때 | 좋음 | 베이킹으로 빠름 | GPU라 빠름 |
| 구현 비용 | ★ 적음 | 중 | △ 큼(C++) |
| 안정성 | △ alpha | ★ 검증됨 | 직접 검증 |

- **Ink API** — 필기감·편의 최상, 구형 지원 약함 + 아직 alpha.
- **Canvas** — 호환성·안정 최강, 비트맵 베이킹으로 빠름, 확대 시 래스터 품질 저하.
- **NDK GL** — 품질·호환·확대·성능의 균형, 대신 C++ 유지비.

자세한 근거는 [비교 문서](docs/drawing-engine-comparison.md) 참고.

## 모듈 구조

```
DrawEngineLab/
├── app/                 진입점 · 홈 화면 · 버전 라우팅
├── core-ui/             공통 UI(툴바·팔레트·줌팬·S Pen 컨트롤)
├── feature-ink/         Ink API 버전
├── feature-canvas/      순수 Canvas 버전
└── feature-nativegl/    NDK GL(C++/GLES2) 버전
    └── src/main/cpp/    네이티브 스트로크 엔진 + GLES2 셰이더
```

## 기술 스택

- Kotlin · Jetpack Compose (Material 3)
- [Jetpack Ink API](https://developer.android.com/jetpack/androidx/releases/ink) `1.1.0-alpha10`
- NDK (C++17) · CMake · OpenGL ES 2.0 · JNI
- Gradle 버전 카탈로그 · 멀티모듈 (AGP 9.4.1)

## 빌드 · 실행

```bash
./gradlew :app:assembleDebug
```

또는 Android Studio에서 프로젝트를 열고 `app` 실행. 홈 화면에서 세 버전 중 하나를 선택해 비교한다.

- `minSdk` 등 세부는 각 모듈 `build.gradle.kts` 참고.
- NDK GL 버전은 빌드 시 NDK·CMake가 필요하다(Android Studio SDK Manager에서 설치).

## 주의 (클린룸)

이 저장소는 삼성 노트의 역공학 **분석 결과**(구조·기법)만 담으며, 삼성의 디컴파일 코드나 `.so` 바이너리는 포함하지 않는다. 세 구현은 모두 공개 기술로 작성한 독립 코드다. 역공학 분석 문서는 상호운용·연구 목적의 구조 서술이며 실행 가능한 삼성 코드를 재현하지 않는다.
