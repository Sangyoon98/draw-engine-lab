# 필기 저장 방식 조사 — 삼성 노트 / 스마트러닝 / Ink

조사일 2026-11-03. 목적: 세 곳의 필기 저장 구조를 비교하고, 스마트러닝 기존 데이터를 Ink로 변환해 저장·복원할 수 있는지 판단.

## 한 줄 결론
세 곳 모두 **"그림(픽셀)"이 아니라 "입력 좌표 + 펜 정보"를 저장하고, 열 때 엔진이 다시 그리는 방식**이다. 차이는 좌표에 무엇을 같이 담느냐(필압·시간 등)뿐이라, **스마트러닝 → Ink 변환은 가능**하다. 단 스마트러닝은 x·y만 있어 필압·속도 표현은 복원할 게 없다.

## 1. 삼성 노트 — `.sdocx` (ZIP 컨테이너 + 자체 바이너리)
근거: 설치된 Samsung Notes APK의 `libSPenWDoc.so` / `libSPenModel.so` / `libSPenSDoc.so` 문자열(디컴파일 없이 `strings`로 확인).

| 파일(ZIP 내부) | 역할 |
|---|---|
| `note.note` | 노트 헤더·고정 데이터(`formatVersion` / `minFormatVersion`, 방향 등) |
| `pageIdInfo.dat` | 페이지 순서 |
| `*.page` | 페이지별 데이터 — 레이어(`LayerDoc`) → 객체 리스트(`ObjectStroke` / `ObjectTextBox` / `ObjectShape` / `ObjectImage`), 객체마다 `*BinaryHandler`가 직렬화 |
| `media/mediaInfo.dat` + 미디어 | 이미지·썸네일 |
| `attach/attachInfo.dat` | 첨부 |
| `end_tag.bin`, `size.dat`, `state.dat` | 앱 버전·포맷 정보, 크기, 상태 |

- **획(ObjectStroke) 저장 필드**: 점 좌표(`PointF`) · 필압(`pressures`) · 타임스탬프(`timeStamps`) · 기울기(`tilts`) · 방향(`orientations`) · 펜 이름(`penName`) · 굵기(`penSize`) · 색(`color`, `colorType`) · 입자 크기(`particleSize`). → **원시 입력을 그대로 저장**하고, 열 때 `penName`의 펜 엔진(.so)이 다시 그린다.
- 텍스트도 객체로 저장(`TextSpanInfo` / `TextParagraphInfo`).
- 버전 호환: `formatVersion` / `minFormatVersion` / `minUnknownVersion`으로 하위·상위 호환 판정.
- 암호화(노트 잠금): `WriteEncryptionInfo` / `encrypt1~7`.
- 구형 포맷: `.spd`, `.sdoc`(`content.dat` / `doc.dat` / `text.dat` / `searchData.dat`) 로더도 남아 있음.
- ⚠️ 미검증: 실제 `.sdocx` 파일을 열어 보진 않았다. 노트 하나를 "Samsung Notes 파일"로 내보내 압축 해제하면 위 구조를 실물로 확인할 수 있다(바이너리 내부 레이아웃은 비공개).

## 2. 스마트러닝 — Gson JSON, 서버 저장
근거: `smartlearning-android-ms` 코드(`.../lecture/note/` 하위).

- **모델**: 페이지 = `DrawNote(size=[w,h], shapes)` / 획 = `Shape(isEraser, segments, strokeColor "#RRGGBBAA", strokeWidth(px), type="Pen")` / 점 = `Segment(a=[x,y])` (`drawing/vo/Shape.kt`, `DrawNote.kt`).
- **점 수집**: `onTouchEvent`마다 `x, y` 하나만 저장(`DrawingView.kt:140`). **필압·시간·historical 점 없음** → 빠르게 그으면 점이 듬성듬성.
- **저장**: JSON 문자열을 `study/lec_my_note.asp`에 `@FormUrlEncoded` 필드로 전송(`lec_note_type=D`, `mode=S`, `chr_cd`/`lec_cd`/`note_page`). 서버 버퍼 한계로 **10만 자씩 나눠 순차 전송**(`FrameNoteViewModel.kt:228`). 기기엔 저장 안 함.
- **포맷 공유**: iOS와 같은 JSON(색을 RGBA로 맞춘 이유).
- **복원**: JSON → `DrawNote` → 저장 당시 `size` 기준으로 현재 화면 크기에 맞게 스케일(`DeviceProvider.calculate`) → 획 객체로 재생. 단 **불러온 획은 undo 불가**(현재 세션 획만).
- **지우개**: 좌표를 가진 `isEraser=true` 획으로 저장 → 재생 시 `PorterDuff.CLEAR`로 다시 지움. 그래서 획 순서가 결과를 좌우하고, 지운 것도 데이터가 계속 쌓인다.
- JPEG는 "다운로드" 내보내기 전용(`Pictures/MegaNote`), 다시 읽지 않음.

## 3. Ink — `ink-storage` (gzip 압축 protobuf)
근거: [Ink 상태 보존 가이드](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-state-preservation), [`androidx.ink.storage`](https://developer.android.com/reference/kotlin/androidx/ink/storage/package-summary).

- 획 = **`Brush` + `StrokeInputBatch`**만 저장하면 복원된다(메시는 저장 안 함, 열 때 재계산).
  - `StrokeInputBatch.encode()` / `StrokeInputBatch.decode()` → gzip 압축된 `ink.proto.CodedStrokeInputBatch`.
  - `Brush`는 가이드 권장대로 직접 저장: 브러시 종류(enum) · `colorLong` · `size` · `epsilon`. (`BrushFamily.encode()`도 있지만 스톡 브러시만 쓰면 enum이 효율적)
- 입력 점(`StrokeInput`) 필드: x · y · 경과 시간(ms) · 도구 종류 · 필압 · 기울기 · 방향 · `strokeUnitLengthCm`.
- 페이지·문서 구조, 텍스트, 이미지는 **Ink가 제공하지 않음** → 앱이 직접 설계해야 함.
- 우리 프로젝트엔 `ink-storage` 의존성이 아직 없음(1.1.0-alpha10에 있음).

## 4. 세 방식 비교

| | 삼성 노트 | 스마트러닝 | Ink |
|---|---|---|---|
| 저장 단위 | 노트 파일 `.sdocx`(ZIP) | 페이지별 JSON(서버) | 획별 바이너리(컨테이너는 앱이) |
| 인코딩 | 자체 바이너리 | Gson JSON 텍스트 | gzip protobuf |
| 점 데이터 | x·y·필압·시간·기울기·방향 | **x·y만** | x·y·시간·필압·기울기·방향 |
| 펜 정보 | 펜 이름 + 굵기 + 색 | 색 + 굵기(펜 1종) | 브러시(종류·색·크기·epsilon) |
| 지우개 | 객체 편집으로 반영 | 지우개 획 append(CLEAR 재생) | 기본 없음(앱 구현) |
| 텍스트·이미지 | 객체로 함께 저장 | 없음 | 없음(앱 구현) |
| 다시 편집 | 가능 | 가능(불러온 획 undo 불가) | 가능 |
| 크기 | 작음(바이너리) | 큼(텍스트, 10만 자 분할 필요) | 작음(압축 바이너리) |

## 5. 스마트러닝 → Ink 변환 가능한가?

**가능.** 스마트러닝도 좌표를 저장하므로 획 단위로 1:1 변환된다.

| 스마트러닝 | Ink | 처리 |
|---|---|---|
| `segments[i] = [x, y]` | `StrokeInput(x, y, …)` | `DeviceProvider`와 같은 방식으로 화면 크기 스케일 후 입력 |
| (없음) 시간 | `elapsedTimeMillis` | **합성 필요** — Ink는 시간이 단조 증가해야 함. 점 간 거리 기반으로 일정 속도로 생성 |
| (없음) 필압 | `pressure` | `NO_PRESSURE` 또는 고정값 → 굵기 일정(원본과 같은 모양) |
| `strokeColor "#RRGGBBAA"` | `Brush.colorLong` | RGBA → ARGB 변환 |
| `strokeWidth` (px) | `Brush.size` | 같은 좌표계라 그대로(스케일 비율만 곱함) |
| `type = "Pen"` | 스톡 브러시 1종 | 원본이 일정 굵기라 `marker`/`pressurePen`(필압 없음) 중 시각 비교로 선택 |
| `isEraser = true` | Ink에 해당 없음 | **ClearOp 레이어로 변환**(이 프로젝트 `feature-ink`의 픽셀 지우개와 같은 방식) → 결과 화면 동일. 또는 지우개와 겹친 획을 지워 데이터 정리(부분 지움은 근사) |

**주의할 점**
- **모양이 살짝 달라질 수 있음**: 스마트러닝 점은 이벤트당 1개라 듬성듬성하고 직선(`lineTo`)으로 이어 그렸다. Ink는 자체 스무딩을 하므로 곡선이 더 부드럽게 바뀐다. 실제 노트 몇 개로 시각 비교가 필요하다.
- **역변환(Ink → 스마트러닝 JSON)**: x·y만 남기면 되므로 가능하지만, 새로 그린 필압·시간 정보는 버려진다. iOS·서버 호환을 유지하려면:
  - (권장) 기존 JSON에 선택 필드 추가(`p`, `t` 등). 기존 파서는 모르는 필드를 무시하므로 하위 호환(Android Gson ✅, iOS Codable 기본 동작 ✅ — iOS 측 확인 필요).
  - 또는 `StrokeInputBatch.encode()` 결과를 base64로 별도 필드에 저장(용량 작음, 대신 iOS가 읽으려면 Ink iOS/proto 필요).
- **서버 분할 전송**: Ink 바이너리는 JSON보다 훨씬 작아 10만 자 분할 이슈가 줄어든다(필드 추가 방식이면 오히려 커짐 — 트레이드오프).

## 6. DrawEngineLab 구현 현황 (2026-11-03)
공통: `:core-notefile` 모듈 — 엔진 중립 모델 `NoteDoc` + 코덱 2종. 저장·불러오기는 시스템 파일 선택기(SAF, 권한 불필요).

| 버전 | 저장 | 불러오기 |
|---|---|---|
| Canvas | **스마트러닝 JSON** (`SmartLearningJson`, 스키마 동일: size/shapes/segments/RGBA/strokeWidth/isEraser/type) | 스마트러닝 JSON, Ink 노트(`.inknote`) |
| Ink | **Ink 노트** `.inknote` (`InkNoteFile`: 획마다 브러시 + `StrokeInputBatch.encode()` base64, 지우개·텍스트 포함) | Ink 노트, 스마트러닝 JSON |
| NDK | **삼성 노트 `.sdocx`** (`SdocxFile`) — 삼성 노트 앱에서 열림 확인 | `.sdocx`(삼성 노트에서 내보낸 파일 포함), 스마트러닝 JSON, Ink 노트 |

기기 확인(SM-P610): Canvas → 스마트러닝 JSON 저장 → Ink에서 열림(위치·색·형광펜 투명도·지우개 구멍 동일). Ink → `.inknote` 저장 → Canvas에서 열림(필압 펜·텍스트 포함). 같은 획 기준 JSON 4.7KB / `.inknote` 2.5KB.
- 변환 규칙: 스마트러닝 "Pen" → Canvas 볼펜(최대 필압) / Ink marker, 시간은 거리 기반 합성. 형광펜은 스마트러닝에 종류가 없어 투명도를 색에 구워 저장. 텍스트는 스마트러닝 형식에 저장 안 됨(안내 토스트).
- 크기 다른 캔버스: 저장 당시 size 기준 비율 유지 스케일(스마트러닝은 x·y 독립 스케일 — 차이 있음).

### `.sdocx` 쓰기 가능성 (공개 역공학 자료 기준)
[twangodev/sdocx](https://github.com/twangodev/sdocx)(GPL-3.0, 읽기·렌더 도구)의 포맷 문서에 따르면 무결성은 **비밀키 서명이 아니라 SHA-256 체인**이다: `note.note` 끝 32B = 앞부분 해시, 객체 해시 = SHA256(UUID + 수정시각), 레이어/페이지 해시 = 하위 해시 연결의 SHA256, `pageIdInfo.dat`가 이를 연결. 획은 `x,y`(Q10.5 델타 압축 가능)·필압·시간·기울기·방향 + 펜 이름 ID·ARGB·굵기. → 직접 계산 가능하므로 **삼성 노트가 여는 파일을 만드는 것은 이론상 가능**. 코드는 참고만 하고(라이선스) 포맷 사실로 직접 구현. 실제 삼성 노트에서 내보낸 `.sdocx`를 템플릿·검증용으로 사용 예정.

### `.sdocx` 구현 결과 (2026-11-03)
- **읽기**: ZIP → `pageIdInfo.dat` 페이지 순서 → 페이지 → 레이어 → 객체(재귀) → 획(type 1) 디코드. 압축(Q10.5 좌표·Q3.12 필압 델타)·비압축 모두 지원. 펜 이름은 `note.note` 문자열 테이블에서(FountainPen → 필압 펜, Marker → 형광펜 등). 삼성 노트가 내보낸 실제 파일: 26획(만년필 21, 형광펜 5) 정상 표시.
- **쓰기(템플릿 방식)**: 삼성 노트가 만든 파일에서 `note.note`·`end_tag.bin`·미디어 목록·페이지/레이어 헤더를 가져오고, 획 객체와 해시(객체·레이어·페이지·`pageIdInfo`)만 새로 생성. 템플릿은 획을 뺀 버전을 `core-notefile/src/main/assets/sdocx_template.sdocx`로 보관.
- **검증**: 독립 Python 검증기로 해시 체인 확인(삼성 원본·우리 파일 모두 통과) → **삼성 노트 앱에서 import 성공**, 획 위치·색·형광펜 동일하게 표시.
- 굵기 보정: 삼성 펜 size 대비 실제 두께가 만년필 약 ½, Marker4 약 0.7 → 변환 상수로 보정(SM-P610 눈대중, 펜 추가 시 재측정).
- 한계: 쓰기 시 펜은 템플릿 문자열 테이블에 있는 만년필·Marker4 두 종만 사용, 텍스트 박스·지우개 획은 저장 안 함(삼성은 지우개를 획 편집으로 처리). 비공개 포맷이라 삼성 노트 업데이트로 깨질 수 있음.

## 7. 다음 단계(제안)
1. 실제 `.sdocx` 내보내기 → 압축 해제로 삼성 구조 실물 확인.
2. DrawEngineLab Ink 버전에 **스마트러닝 JSON 불러오기** 프로토타입: 실제 노트 JSON 몇 개를 넣어 원본(Canvas 재생)과 Ink 변환 결과를 나란히 비교.
3. 저장 포맷 결정: 기존 JSON 확장 vs Ink 바이너리 병행 — iOS 팀과 협의 필요.
