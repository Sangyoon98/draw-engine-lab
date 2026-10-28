# 삼성노트 앱 역공학 분석 (클로닝 검토용)

## 1. 목적
역공학으로 **삼성노트 드로잉 구현 방식을 분석**하여 클로닝 가능 여부와 방법을 검토한다.

## 2. 분석 방법
- 대상: `com.samsung.android.app.notes` (base.apk 약 207MB), **본인 기기에서 adb로 추출**.
- 기법:
  1. 네이티브 `.so` 목록·심볼(nm/objdump + demangle)
  2. DEX 문자열/클래스 분석(렌더 서피스·드로우루프 식별)
  3. **Ghidra headless 디컴파일**(드로잉 관련 `.so` 19종 → 유사 C 추출)
- 한계: `.so`가 stripped라 **코드가 사람이 읽을 소스로는 안 나옴**(유사 C 의사코드 + 난독). 레이어·심볼·렌더 전략·구조까지 확인, 내부 수식은 미확인.

## 3. 분석 결과 — 아키텍처

```
[터치]  Java/Kotlin (com.samsung.android.sdk.pen.engine.*)
  · SpenWritingSurfaceView / SpenViewCore — SurfaceView + EGL, MotionEvent 수신
        │
  [입력 보정]  libSPenResampleWrapper.so — 리샘플/보간(저지연·부드러움)
        │ JNI
  [모델]  libSPenModel / libSPenEngine (C++) — 스트로크 데이터·기하 누적, 문서 모델
        │
  [펜]  펜마다 독립 .so (SPen::FountainPen 등) + 전용 GL StrokeShader
        │   압력/기울기/곡선/그라데이션 옵션 → 가변폭 기하 생성
  [합성/렌더]  libSPenComposer / libSPenGraphics / libSPenPaintingCore (C++ + GLES)
        │
  [출력]  프론트버퍼 저지연(SpenFbrDrawPad) + 드로우루프 기기별 선택
          AGLL(저지연) / HWUI / HwBuffer / Texture / Surface
```

### 한 획이 그려지는 흐름
1. **입력**: 펜/손 터치가 Java `SpenWritingSurfaceView`로 수신(안드로이드는 MotionEvent를 JVM에 먼저 전달).
2. **리샘플**: `libSPenResampleWrapper`가 입력점 보간/예측(저지연·부드러운 곡선).
3. **JNI → 네이티브**: 점들을 C++ 엔진으로 전달.
4. **모델링**: `libSPenModel`/`libSPenEngine`이 스트로크로 누적·기하 계산.
5. **펜별 처리**: 선택된 펜 `.so`가 자기 로직으로 가변폭·효과 계산.
6. **렌더**: 펜 전용 **OpenGL 셰이더**로 `Composer/Graphics/PaintingCore`가 GL 합성.
7. **저지연 출력**: wet-ink는 **프론트버퍼**에 즉시(지원 시 AGLL, 구형은 HWUI 폴백), 완료 시 메인 버퍼로 합성(dry) + 모델 저장.

### 확정된 근거
- **렌더 = 프론트버퍼 저지연**: `setFrontBufferRenderingEnabled`, `onDrawFrontBufferedLayer`, `SpenFbrDrawPad`.
- **드로우루프 기기별 전략**: `SpenDrawLoopAGLL`(Android Graphics Low Latency) / `SpenDrawLoopHWUI`(구형 폴백) / `HwBuffer` / `Texture` / `Surface`.
- **펜마다 네이티브 `.so`** (약 20종): FountainPen · BrushPen · InkPen/InkPen2 · Pencil/Pencil2 · ColoredPencil · Marker1~4 · MontblancFountainPen · MontblancCalligraphyPen · ChineseBrush · OilBrush3 · Crayon2 · MagicPen · LaserPen · AirBrushPen · ObliquePen 등.
- **엔진/모델/합성 네이티브**: libSPenEngine · libSPenModel · libSPenComposer · libSPenGraphics · libSPenPaintingCore.
- **지우개 네이티브**: `libSPenEraser.so`. **입력 리샘플**: `libSPenResampleWrapper.so`. **필기인식 ML**: `libSPenONNXRuntime.so`(약 13MB) + HandwritingConverter/Beautifier.
- **자체 포함 라이브러리**: libpng/jpeg/xml 번들 → C++ 코어가 플랫폼 독립적(Windows판 공유 뒷받침).

### 네이티브 API 심볼 예 (만년필)
- `SPen::FountainPen::GetStrokeDrawableGL()` / `GetOutlineDrawableGL()` / `GetPreviewDrawableGL()` — GL로 획/외곽/미리보기 렌더
- `SetFixedWidth` / `IsFixedWidthEnabled` / `IsCurveEnabled` — 굵기·곡선 옵션
- `SetGradientColors` / `Rainbow*` — 그라데이션·무지개
- `SetTipLength` / `SetDpi` / `GetMin~MaxPxSize` — 펜촉·해상도·굵기 범위
- `class FountainPenStrokeShader` — 전용 OpenGL 셰이더

## 4. Ghidra 디컴파일 실측 결과
드로잉 관련 `.so` 19종을 Ghidra headless로 디컴파일(유사 C 추출). **결과: 전부 디컴파일은 되나 난독 범벅 → 재사용 불가.**

| 라이브러리 | 줄 수 | 라이브러리 | 줄 수 |
|---|---|---|---|
| libSPenFountainPen | 100,621 | libSPenDrawing | 88,459 |
| libSPenPencil2 | 82,441 | libSPenGraphics | 72,280 |
| libSPenPaintingCore | 70,185 | libSPenBrushPen | 59,800 |
| libSPenPenCommon | 56,658 | libSPenObliquePen | 53,975 |
| libSPenInkPen2 | 44,079 | libSPenMarker | 36,189 |
| libSPenInkPen | 30,024 | libSPenMontblancFountainPen | 29,436 |
| libSPenChineseBrush | 25,633 | libSPenColoredPencil | 23,283 |
| libSPenCrayon2 | 21,284 | libSPenMarker2 | 18,340 |
| libSPenPencil | 17,203 | libSPenEraser | 14,907 |
| libSPenDefaultPen | 12,407 | **합계** | **약 93만 줄** |

- 난독 지표(libSPenEraser 기준, 1,215함수): 이름 없는 함수(FUN_/sub_) 299, `undefined` 타입 1,444회, raw 포인터 캐스트 1,534회, goto 149, 익명 전역(DAT_) 89.
- 즉 `undefined4 uVar1 = *(undefined4*)(param+0x18)` 류 + goto + 익명 전역 덩어리. **타입·이름·구조 소실.**
- 큰 라이브러리(ONNXRuntime 13MB, Model 4.9MB, Composer 6MB 등)는 더 거대·난독이라 제외.

## 5. 결론 — 클로닝 가능 여부
- **코드 복제 불가 & 부적절**
  - `.so`는 stripped 기계어 → 디컴파일해도 **재사용 가능한 소스가 아님**(의사코드 난독).
  - 독점 코드를 참고/유추해 가져오면 **클린룸 오염 + 저작권 침해**.
- **실효 경로 = 구조 파악 후 기법을 직접 재현(클린룸)**
  - 삼성 구조(Java surface → JNI → C++ 엔진 → 펜별 GL 셰이더 + 프론트버퍼 저지연)를 **우리 코드로** 재현.
  - 샘플앱 **NDK GL 버전**으로 이 구조 재현·입증 완료(3종 비교 문서 참고).
- **분석의 산출물**: "삼성노트 드로잉 = 네이티브 C++ 엔진 + 펜별 GL 셰이더 + 프론트버퍼 저지연 방식. 코드는 닫혀 있으나 **구조·기법은 공개 기술이라 클린룸 재현 가능**"임을 확인.

## 6. 참고 — 스마트러닝 현재 방식과의 관계
- 스마트러닝 Android 드로잉(`DrawingView.kt`)은 **MotionEvent + Canvas + 비트맵** 방식(삼성노트 입력층과 같은 계열이나, 삼성은 렌더를 네이티브 GL로 처리).
- 삼성노트 수준의 필기감/저지연을 원하면: ① Ink API(공식 엔진) ② 네이티브 C++/GL 직접 구현(샘플 NDK GL) 중 선택 — 3종 비교 문서 참고.
