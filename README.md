# EV Dash

Daly BMS(BLE)에서 전압·전류·SOC를 읽고, 폰 GPS로 속도를 표시하는 전기차 계기판 앱.

- 화면: 전압 / 전류 / GPS 속도 3분할, 가로 고정, 항상 켜짐
- BMS: Daly UART-over-BLE (service `fff0`, notify `fff1`, write `fff2`, 커맨드 `0x90`)
- minSdk 26 (Android 8.0) / targetSdk 34 / compileSdk 35

---

## APK 만드는 법 — 방법 1: GitHub Actions (Android Studio 필요 없음)

가장 쉬운 길. 폰만 있어도 됨.

1. GitHub에 새 repo 생성 (private 가능)
2. 이 폴더 전체를 그대로 push

```bash
cd EvDash
git init
git add .
git commit -m "EV Dash 초기 커밋"
git branch -M main
git remote add origin https://github.com/<본인계정>/<repo이름>.git
git push -u origin main
```

3. repo의 **Actions** 탭 → `Build APK` 워크플로가 자동 실행됨 (5~8분)
4. 초록불 뜨면 해당 실행 페이지 하단 **Artifacts → `evdash-apk`** 다운로드
5. zip 안의 `app-debug.apk`를 폰으로 옮겨서 설치
   (설정 → "출처를 알 수 없는 앱 설치" 허용 필요)

수정할 때마다 push하면 새 APK가 자동으로 나옴.

---

## APK 만드는 법 — 방법 2: Android Studio

1. Android Studio에서 `File → Open` → `EvDash` 폴더 선택
2. Gradle sync가 끝날 때까지 대기 (첫 실행은 SDK 다운로드로 몇 분 걸림)
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`
4. `app/build/outputs/apk/debug/app-debug.apk` 생성됨

USB로 폰 연결 후 `Run` 버튼을 눌러 바로 설치해도 됨.

---

## APK 만드는 법 — 방법 3: 커맨드라인

Android SDK와 JDK 17이 설치돼 있어야 함.

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

---

## 서명에 대해

`release` 빌드도 debug 키로 서명하게 해뒀다 (`app/build.gradle.kts`).
개인 사용/팀 내부 테스트에는 이게 제일 편하다. 다만 debug 키 APK는 Play 스토어 업로드가
불가능하고, 나중에 진짜 keystore로 바꾸면 **기존 앱을 삭제 후 재설치**해야 한다.

---

## BMS 데이터가 안 들어올 때

Daly는 펌웨어별로 프로토콜이 조금씩 다르다. 순서대로 확인:

1. **nRF Connect** 앱으로 BMS에 붙어서 service/characteristic UUID가
   `fff0` / `fff1` / `fff2` 맞는지 확인
2. 앱 실행 상태로 `adb logcat -s DalyBLE` → `RX:` 로그에 원본 바이트가 찍힌다
3. 응답 프레임이 `A5 01 90 08 ...` 형태가 아니면
   `DalyBleManager.tryParseBuffer()`의 오프셋을 실측값에 맞게 고치면 된다

현재 파싱 기준:

| 항목 | 위치 | 변환 |
|---|---|---|
| 전압 | frame[4..5] | raw / 10 (V) |
| 전류 | frame[8..9] | (raw − 30000) / 10 (A) |
| SOC | frame[10..11] | raw / 10 (%) |
