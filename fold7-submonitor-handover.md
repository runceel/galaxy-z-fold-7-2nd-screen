# Galaxy Z Fold7 を Windows サブモニター化する — 開発引き継ぎメモ

- 作成日: 2026-10-18
- 作成者: Kazuki Ota
- ステータス: 調査完了 / 未実装（コマンド・構成はいずれも未検証）
- 対象 PC: Windows on Arm（Arm64）／ AMD CPU 搭載機（x64）の 2 台

---

## 1. ゴール

Galaxy Z Fold7 のメイン画面を、Windows PC の **拡張ディスプレイ（サブモニター）** として使えるようにする。

Samsung 純正の「Second Screen」機能は Galaxy Tab シリーズ専用で Fold は対象外のため、自作する。

## 2. 要件・制約（スコープを意図的に絞っている）

| # | 制約 | 理由 / 効果 |
|---|---|---|
| 1 | **USB 有線接続のみ** | 機器探索、暗号化、認証、Wi-Fi の不安定さへの対策が不要 |
| 2 | **自分専用（配布しない）** | ドライバーの自作・署名、インストーラー、多機種対応が不要 |
| 3 | **Fold を開いた状態のみ対応** | 解像度を 2184×1968 に固定。開閉検知と表示モードの切り替えが不要 |
| 4 | **タッチ入力なし（表示専用）** | 通信は PC → Fold の一方通行。操作は PC のマウスとキーボードで行う |
| 5 | **Fold の開発者モードは常用不可** | 一定時間で自動的に無効になる（端末管理ポリシーと思われる）。adb に依存しない構成にする |
| 6 | **PC は 2 種類: Windows on Arm（Arm64）と AMD CPU 搭載機（x64）** | 違うのは圧縮方式と開発環境だけで、それ以外の構成は共通（4.2 / 5.1 参照）。Arm64 では NVENC / QSV / AMF と Android エミュレーターが使えない |

> 開発者モードの自動無効化は、会社の端末管理ポリシーによる意図的な制限の可能性が高い。有効なまま保つような回避策は取らないこと。

## 3. アーキテクチャ

```
[Windows PC（Arm64 / AMD x64 共通）]
  Virtual-Display-Driver   … 仮想モニター（既製品、2184x1968 を登録）
        │
  FFmpeg
    ddagrab（Desktop Duplication による画面取り込み）
      → HW エンコード（Arm64: hwdownload + h264_mf ／ AMD: h264_amf）
      → TCP クライアントとして Fold に接続
        │
        │  USB テザリング（PC 側には RNDIS の有線 LAN として見える）
        ▼
[Galaxy Z Fold7]
  自作 Android アプリ
    ServerSocket で待ち受け
      → Annex-B の H.264 ストリームを NAL 単位に分割
      → MediaCodec（低遅延モード）でデコード
      → SurfaceView に全画面表示
```

**自作するのは Fold 側アプリだけ。** PC 側は既製のドライバーと FFmpeg のコマンドで構成する。

### 接続の向きについて

- **Fold 側が待ち受け、PC 側から接続する。** PC が待ち受ける構成にすると、RNDIS のネットワークが「パブリック」扱いになり、Windows ファイアウォールの許可設定が必要になりがちなため。
- PC から見た Fold の IP は、USB テザリング用アダプターの **デフォルトゲートウェイ**（`ipconfig` で確認）。アドレスは機種や環境で変わるので、決め打ちしないこと。

## 4. 構成要素の詳細

### 4.1 仮想モニター — Virtual-Display-Driver

- リポジトリ: https://github.com/VirtualDrivers/Virtual-Display-Driver （MIT ライセンス）
- IddCx を使う UMDF ドライバー。x64 と Arm64 に対応（Arm64 対応は 24.10.16-beta 以降）。
- インストール: `winget install --id=VirtualDrivers.Virtual-Display-Driver -e`
- 事前に Microsoft Visual C++ 再頒布可能パッケージが必要。
- 設定ファイル `C:\VirtualDisplayDriver\vdd_settings.xml` で、解像度 **2184×1968** を追加する（カスタム EDID でも可）。
- Windows の「ディスプレイ設定」で「表示画面を拡張する」を選ぶ。

> ドライバーは自作しない。自作するとテスト署名モードが必要になり、そのためにセキュアブートを無効化しなければならない。会社 PC では現実的ではない。

### 4.2 取り込みと圧縮 — FFmpeg

PC ごとに違うのは **FFmpeg のビルドとエンコーダーだけ**。仮想モニター、伝送方法、Fold 側アプリは共通。

| PC | FFmpeg ビルド | 第 1 候補 | 代替 |
|---|---|---|---|
| Windows on Arm（Arm64） | Arm64 版 | `h264_mf -hw_encoding 1` | `libx264` |
| AMD CPU + Radeon（内蔵 / 外付け） | x64 版 | `h264_amf` | `h264_mf -hw_encoding 1` → `libx264` |
| AMD CPU + NVIDIA 外付け GPU | x64 版 | `h264_nvenc` | `h264_mf -hw_encoding 1` → `libx264` |

`ffmpeg -encoders` に載っていても、その PC で実際に動くとは限らない。必ず短い試し圧縮で確認すること。

#### Arm64 PC

- Arm64 版ビルドの例: https://github.com/tordona/ffmpeg-win-arm64

**コマンド案（未検証）:**

```bat
ffmpeg -f lavfi -i ddagrab=output_idx=1:framerate=60 ^
  -vf hwdownload,format=bgra ^
  -c:v h264_mf -hw_encoding 1 -scenario display_remoting -b:v 30M ^
  -f h264 "tcp://<FoldのIP>:5000"
```

- `output_idx` は仮想モニターの番号。環境によって変わるので要確認。
- `ddagrab` はマウスカーソルも描画する（`draw_mouse` が既定で有効）。
- `ddagrab` は D3D11 のフレームを出力するので、`hwdownload` で通常のメモリにコピーしてから `h264_mf` に渡す。コピーが 1 回増える分、負荷が上がる。
- `-scenario display_remoting` が効くかどうかは環境次第。

#### AMD CPU 搭載 PC（x64）

- FFmpeg 公式サイトのダウンロードページで案内されている Windows x64 ビルドを使う。`ffmpeg -encoders | findstr amf` で `h264_amf` が入っているか確認する。
- AMD の GPU ドライバー（Adrenalin）は最新にしておく。AMF はドライバーに含まれている。

**コマンド案（Radeon の場合・未検証）:**

```bat
ffmpeg -f lavfi -i ddagrab=output_idx=1:framerate=60 ^
  -c:v h264_amf -usage ultralowlatency -quality speed -rc cbr -b:v 30M -bf 0 -g 60 ^
  -f h264 "tcp://<FoldのIP>:5000"
```

- `ddagrab` が出力する D3D11 のフレームを、そのまま AMF に渡す想定（GPU 内で完結し、`hwdownload` が不要）。エラーになる場合は、Arm64 と同じく `-vf hwdownload,format=bgra` を挟む。
- NVIDIA の外付け GPU がある場合は、エンコーダーを `-c:v h264_nvenc -preset p1 -tune ull -bf 0 -g 60 -b:v 30M` に差し替える。
- **GPU が複数ある機種（内蔵 Radeon と外付け GPU など）では注意。** 仮想モニターを描画する GPU（VDD の GPU 指定）と、`ddagrab` とエンコードで使う GPU をそろえる。そろっていないとエラーや黒画面になりやすい。

**HW エンコードが使えない場合の代替:**

```bat
-c:v libx264 -preset ultrafast -tune zerolatency -bf 0 -g 60 -b:v 30M
```

CPU 負荷が高いので、30fps から始める。

**圧縮が必須な理由（帯域の試算）:**

| 条件 | 無圧縮での帯域 |
|---|---|
| 2184×1968 RGB 60fps | 約 6.19 Gbps |
| 2184×1968 RGB 120fps | 約 12.38 Gbps |
| 2184×1968 NV12 60fps | 約 3.09 Gbps |

USB 接続でも無圧縮は現実的ではない。ただし通信が安定しているので、回線状況に応じた画質調整は不要。ビットレートを高めに固定してよい。

### 4.3 伝送 — USB テザリング

- Fold の「設定」→「接続」→「モバイルホットスポットとテザリング」→「USB テザリング」をオンにする。**開発者モードは不要。**
- PC 側には RNDIS の有線 LAN アダプターとして見える。
- **注意:** PC のインターネット通信が Fold 経由になり、モバイル通信量を消費することがある。テザリング側アダプターのインターフェイスメトリックを大きくして回避する。
  ```powershell
  Get-NetIPInterface | ? InterfaceAlias -like "*Remote NDIS*"   # インターフェイス名を確認
  Set-NetIPInterface -InterfaceAlias "<上で確認した名前>" -InterfaceMetric 9999
  ```
- spacedesk のマニュアルでは、USB テザリングは有線 LAN 直結に次ぐ「中程度」の性能で、ルーター経由の Wi-Fi より上の評価。

### 4.4 Fold 側アプリ（自作・Kotlin）

規模の見込みは 100〜200 行程度。

**必須機能:**

1. `ServerSocket(5000)` で待ち受け、PC からの接続を受け付ける。
2. 受信したバイト列をスタートコード（`00 00 00 01` / `00 00 01`）で区切り、NAL ユニットに分割する。
3. 最初の SPS/PPS を受け取ったら、MediaCodec を `video/avc` で `configure` する。
4. `MediaFormat.KEY_LOW_LATENCY = 1` を設定する（Android 11 以降。チップ側の対応が必要なので実機で確認）。
   - 対応しているかは `CodecCapabilities.isFeatureSupported(FEATURE_LowLatency)` で確認できる。
5. 出力先は `SurfaceView`。`releaseOutputBuffer(index, true)` ですぐに描画する。
6. 全画面表示（immersive モード）、画面の向きを固定、画面を常時点灯（`FLAG_KEEP_SCREEN_ON`）。
7. **画面上にデバッグ情報を表示する**（受信 fps、ビットレート、デコード遅延、エラー）。開発者モードが切れると logcat を読めなくなるので必須。

**接続が切れたときの処理:** 切断されたらデコーダーを解放して待ち受けに戻る。PC 側で FFmpeg を再起動すれば再接続できるようにする。

**対象外（今回は実装しない）:** タッチの送り返し、開閉時の解像度切り替え、カバー画面対応、Wi-Fi 接続。

## 5. 開発環境と進め方

### 5.1 開発環境（PC ごとの違い）

**→ アプリ開発は AMD 機で行うのがおすすめ。** エミュレーターが使えるので、実機を使うのは最終確認だけで済む。

#### Arm64 PC

- **Android Studio に Windows Arm64 版はない。** x64 版が互換レイヤー（Prism）上で動くので、やや重い。
- **Android エミュレーターは公式には使えない。** 動作確認は実機で行う。
- APK を実機に入れる方法は次の 2 つ。
  - 開発者モードが有効な時間内に、Android Studio または `adb install` で書き込む。
  - APK ファイルを Fold にコピーし、「提供元不明のアプリのインストール」を許可してインストールする（開発者モードは不要）。
- インストールしたアプリは、開発者モードが無効に戻っても残る想定。

#### AMD CPU 搭載 PC（x64）

- Android Studio は x64 版がそのまま動き、**Android エミュレーターも使える**。Windows の機能「Windows ハイパーバイザー プラットフォーム」を有効にしておく。
- 解像度 2184×1968 の仮想端末を作って開発する。
- エミュレーターに対する adb は、Fold の開発者モードの制限を受けない。Fold アプリは待ち受け側なので、`adb forward tcp:5000 tcp:5000` を実行し、FFmpeg の送信先を `tcp://127.0.0.1:5000` にすれば、PC 本体からエミュレーターに映像を送れる。
- エミュレーターのデコーダーはソフトウェア処理なので、低遅延モードが効くかどうかと実際の遅延は、実機でしか確認できない。

### 5.2 推奨ステップ

| Step | 内容 | 完了条件 |
|---|---|---|
| 0 | 端末管理ポリシーの確認 | USB テザリングと提供元不明アプリのインストールが許可されていること |
| 1 | PC 側だけで検証（2 台とも） | VDD で 2184×1968 の仮想モニターが出ること。各 PC 向けの FFmpeg コマンドで HW エンコード（Arm64: `h264_mf` ／ AMD: `h264_amf`）が動き、ファイル出力（`-f h264 out.h264`）できること |
| 2 | （任意）既製品で全体を仮検証 | spacedesk などで USB テザリング経由の遅延の目安をつかむ |
| 3 | Fold アプリの最小版（AMD 機のエミュレーターで開発） | TCP で受信し、デコードして表示できること |
| 4 | 実機での結合テスト | USB テザリングで 60fps 表示。遅延と画質を計測 |
| 5 | チューニング | ビットレート、GOP、120fps 化、低遅延モードの効果を確認 |

## 6. リスク・未確定事項

| リスク | 影響 | 対応 |
|---|---|---|
| `h264_mf` の HW エンコードが動かない、または遅い（Arm64） | 遅延増、CPU 負荷増 | `libx264` の ultrafast + zerolatency、30fps に落とす |
| `h264_amf` が動かない（ドライバーが古いなど）（AMD） | HW エンコードが使えない | Adrenalin を更新。それでも駄目なら `h264_mf` → `libx264` |
| GPU が複数ある機種で、描画 GPU と取り込み GPU が一致しない（AMD） | `ddagrab` のエラー、黒画面 | VDD の GPU 指定と、エンコードに使う GPU をそろえる |
| 使う FFmpeg ビルドに `ddagrab` が含まれていない | 画面を取り込めない | `ffmpeg -filters \| findstr ddagrab` で確認し、別のビルドを使う |
| Fold7 の MediaCodec で低遅延モードが効かない | デコード遅延が増える | 対応状況を確認。効かなくても出力バッファをすぐ解放する設計で緩和する |
| 端末管理ポリシーで USB テザリングが禁止されている | 伝送経路がなくなる | 私物端末で試す。ポリシーの回避はしない |
| テザリングで PC の通信がモバイル回線経由になる | 通信量を消費 | インターフェイスメトリックを調整する |
| `hwdownload` によるコピーの負荷 | 120fps が厳しい可能性 | まず 60fps を目標にする |

## 7. 将来の拡張（今回は対象外）

- **タッチ入力:** Fold → PC に座標を送り、`InjectSyntheticPointerInput`（Windows 10 1809 以降）で再現する。座標は仮想スクリーン全体の左上が基準。管理者権限で動くウィンドウには通常権限から送れない点に注意。
- **開閉への対応:** Jetpack WindowManager の `WindowInfoTracker` / `FoldingFeature` で開閉状態を検知し、VDD 側の解像度を切り替える。カバー画面は 2520×1080。
- **テーブルトップ姿勢:** `FoldingFeature.State.HALF_OPENED` かつ `Orientation.HORIZONTAL` で判定し、上半分を画面、下半分を操作パネルにする。
- **自作 IDD:** 製品化する場合は IddCx でドライバーを作り、署名する（Microsoft の IddSample をベースにする）。

## 8. 参考資料

- Indirect Display Driver Model Overview（Microsoft Learn）: https://learn.microsoft.com/en-us/windows-hardware/drivers/display/indirect-display-driver-model-overview
- IddSample（Microsoft）: https://learn.microsoft.com/en-us/samples/microsoft/windows-driver-samples/indirect-display-driver-sample/
- Virtual-Display-Driver: https://github.com/VirtualDrivers/Virtual-Display-Driver
- FFmpeg Windows Arm64 ビルド: https://github.com/tordona/ffmpeg-win-arm64
- Low-latency decoding in MediaCodec（AOSP）: https://source.android.com/docs/core/media/low-latency-media
- Make your app fold aware（Android Developers）: https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware
- InjectSyntheticPointerInput（Microsoft Learn）: https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injectsyntheticpointerinput
- spacedesk マニュアル（USB テザリングの性能評価）: https://manual.spacedesk.net/
- Galaxy Z Fold7 仕様（Samsung）: https://www.samsungmobilepress.com/media-assets/galaxy-z-fold7?tab=specs
