# Galaxy Z Fold7 サブモニター化

Galaxy Z Fold7 の開いたメイン画面を、USB 接続で Windows の拡張ディスプレイとして使うためのプロジェクトです。

**P0 の実機確認は未完了です。P1 の実機非依存部分（Android ビルド基盤と Kotlin コア）を先行開発しています。** 実運用の受信・デコード・表示と検証済みの Windows 送信コマンドはまだありません。Windows Arm64 / AMD CPU 搭載 x64 の両方を対象とします。

## 読む順序

| 文書 | 目的・正とする情報 |
| --- | --- |
| [要求仕様](docs/requirements.md) | 対象範囲、制約、要求の識別子、未確定事項 |
| [アーキテクチャ](docs/architecture.md) | 実装者が守る責務境界、接続方向、セッションと資源の寿命 |
| [実装計画](docs/implementation-plan.md) | 依存関係、作業単位、成果物、段階ごとの完了条件 |
| [検証計画](docs/validation-plan.md) | 要求とテストの対応、異常系、性能の測定方法 |
| [セットアップ・運用](docs/operations.md) | 実機検証時の準備、開始・終了、復旧、安全な設定変更 |
| [開発引き継ぎメモ](fold7-submonitor-handover.md) | 計画の入力となった調査資料。記載コマンドと技術的な想定は未検証 |

まず要求仕様とアーキテクチャを読み、実装計画の P0 と先行可能な独立検証の範囲を確認します。入力資料と計画が異なる箇所は、各文書で補正理由や未検証状態を明記しています。独立検証の成功は実機の成立性や受け入れ完了を意味しません。

## 先行実装

- `core/`: Android に依存しない Kotlin の Annex-B / AUD 境界解析、有限バッファ・キュー、単一セッションと無通信期限、診断値、および単体・合成映像テスト。
- `app/`: 独立検証用であることを表示する最小 Activity。ネットワーク権限・待ち受け・デコーダーは未実装で、Android ビルドも依存取得の阻害により未確認。
- Gradle Wrapper と依存版を固定。コアは独立した Gradle 設定を持ち、Android SDK / Google Maven なしでも検証できます。

実行条件・結果・未確認範囲は [P1 独立検証記録](docs/validation-results/2026-10-18-cloud-p1.md)、開発用コマンドは[セットアップ・運用](docs/operations.md)を参照してください。

## ドキュメントの規則

実装時に守る制約と文書の更新規則は [AGENTS.md](AGENTS.md) にあります。ADR、検証結果の記録、検証済み手順への昇格、文書の追加は [project-docs スキル](.agents/skills/project-docs/SKILL.md) の手順に従います。
