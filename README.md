# Komorebi / Honorebi fork

**Komorebi** は、KonomiTV、 EDCB バックエンド、Mirakurun（オプション）に対応した、Android TV 向けの高機能視聴クライアントアプリです。
モダンな UI と直感的なリモコン操作、市販のハイエンドレコーダーを凌駕する高度なストリーミング制御を組み合わせ、これまでにない快適なテレビ視聴体験を提供します。

---

## このフォークについて
このリポジトリは、Komorebi を Android TV / Fire TV で毎日使うために調整した fork です。上流の 1.1.0-beta 系の変更を取り込みつつ、既存のプレイヤー操作感を残し、KonomiTV 系バックエンドとの相性、録画・追いかけ再生、字幕、リモコン操作、起動時の扱いやすさを重点的に直しています。

この fork は、通常の KonomiTV ではなく、作者の環境に合わせて調整している [makeding/HonomiTV](https://github.com/makeding/HonomiTV) fork と組み合わせて利用することを前提にしています。

主な特徴は以下です。

* **Honorebi として共存可能**: アプリ名と applicationId を分離し、元の Komorebi と同じ端末に入れて比較・移行しやすくしています。
* **テレビ視聴中の操作を軽く**: ライブ視聴のサブメニュー、クイックチャンネル切替、ロゴ読み込み、背景同期処理を見直し、視聴中の UI 負荷とフォーカス迷子を減らしました。
* **リモコン前提のライブ導線**: 上キーからすぐ開けるクイックチャンネル切替を追加し、GR と放送中アニメを分けた TV 向けの移動量の少ない構成にしています。
* **追いかけ再生と録画再生を強化**: ライブ画面から現在録画中の番組へ直接入れるようにし、HLS playlist の更新、keep-alive、再準備時の字幕リプレイ抑制など、KonomiTV 系の録画再生で詰まりやすい部分を補強しています。
* **録画プレイヤーの回遊性を追加**: 4x4 のキーフレームグリッド、チャプター/サムネイル検索、再生終了時のシリーズ・最近録画メニュー、次エピソードのカウントダウン再生を追加しています。
* **ARIB 字幕を native 側へ寄せて安定化**: libaribcaption ベースの native デコードとオーバーレイ表示に寄せ、字幕の自動消去、無期限字幕、ホットパスの JSON/base64 変換削減を調整しています。
* **SMB も同じ動画プレイヤーへ統合**: libVLC 依存を外し、SMB 上の動画も ExoPlayer ベースの録画プレイヤーで扱えるようにしました。外部チャプターも録画再生と同じ操作体系で利用できます。
* **オフライン・キャッシュ時の起動を改善**: サーバーに接続できない場合でも、ローカルに残っている録画情報やキャッシュを使って継続できる導線を用意しています。
* **重い機能は日常視聴優先で整理**: `AppContentStore` による共有キャッシュで画面遷移時の待ちを減らし、AI コンシェルジュや辞書解決など、自分の利用に不要な重い機能は無効化しています。

## ⚠️ 初回セットアップとバックエンド環境について
* **初回セットアップ**: インストール後の初回起動時は、**KonomiTV** もしくは **EDCB** および **Mirakurun（オプション）** のサーバー設定が必要です。画面の指示に従ってIPアドレスやポート番号を入力してください。バックエンドにMirakurunを使用していない場合は、MirakurunのIPアドレスとポート番号の入力は不要です。
* **KonomiTV を利用する場合**: この fork は [makeding/HonomiTV](https://github.com/makeding/HonomiTV) fork との併用を前提にしています。録画再生や追いかけ再生など、一部の挙動はこの HonomiTV fork 側の API / ストリーミング実装に合わせています。
* **EDCB を利用する場合**: 録画番組への「直接アクセス」や「トランスコード視聴」を利用するためには、連携スクリプトの配置が必要です。各リリースに添付されている `Komorebi Configurator`（Windows用）または `setup.sh`（Linux用）を実行し、最新の `resolver.lua` を設定してください。
* **この fork の方針**: 上流由来のセットアップ手順は残しつつ、Android TV / Fire TV での日常利用に必要なバックエンド設定とプレイヤーまわりの安定性を優先して調整しています。

### Cloudflare Access（Service Token）

Cloudflare Access でバックエンドを保護する場合は、Access アプリケーションのポリシーで作成した Service Token を許可してください。[Cloudflare の Service Token 手順](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)に従い、設定画面の「接続」→「Cloudflare Access」へ Client ID と Client Secret を**両方**入力します。消去する場合も両方を空にして保存します。

Access を使うバックエンドは、端末が信頼する証明書を提示する `https://host` または `https://host:port` の完全な HTTPS アドレスで設定してください。アドレス内でポートを省略した場合は、接続設定のポート番号を使います（Cloudflare の通常の HTTPS 公開先は `443`）。明示したポートはアドレス側が優先されます。トークンは、設定された KonomiTV / Mirakurun / EDCB / EPGStation の一致する HTTPS origin に対する API、画像、ストリーム、SSE、HonomiTV WebSocket だけに送られます。SMB、第三者画像、更新先、HTTP 接続、別 origin へのリダイレクトには送られません。

---

## 💻 動作環境

以下の環境で動作確認しております。Android 8.0、Fire OS 7以上のOSが必要です。（おおよそ2021年以降の機種であれば利用できると思います）
機種ごとの確認報告などは作者X([@tamago0602](https://x.com/tamago0602))へご連絡いただければ、とても嬉しいです。

* REGZA 55X8900K (Android TV 10)
* Fire TV Stick 4K Max 第一世代 (Fire OS 7 Android 9ベース)
* Fire TV Stick 4K Max 第二世代 (Fire OS 8 Android 11ベース)
* Mirakurun（4.0.0-beta.16）＋KonomiTV v0.13.0 beta
* EDCB-Wine（xtne6f版EDCB＋EMWUI）等の Linux/Windows EDCB 環境
* LinuxネイティブEDCB（xtne6f版EDCB 260331リリース＋EMWUI）環境

---

## 📱 実装済み機能

### 🏠 ホームタブ
アプリ起動時に最初に表示される、Komorebiのポータル画面です。テレビの大画面に最適化されたリッチなUIで、今すぐ見たいコンテンツへ直感的にアクセスできます。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/73a372f0-40dc-45a1-bead-eed42006ac8c" />

* **ダイナミック・ヒーローダッシュボード**: リモコンのフォーカス移動に合わせて、バックグラウンドで高速にAPIと通信し、高画質な背景画像や番組のあらすじ、視聴プログレスバーがシームレスなアニメーションとともに切り替わります。
* **AIコンシェルジュ機能 (Gemini連携)**: 上流に含まれる番組探し支援機能ですが、この fork では日常視聴時の軽さとプレイヤー安定性を優先するため無効化しています。
* **時間連動テーマ**: 朝・昼・夕方・夜の時間帯に合わせて、自動的にUIのカラーパレットや背景のグラデーションが美しく変化する「木漏れ日」「カイル」テーマを搭載。時間帯ごとのエモーショナルな配色で視聴体験を彩ります。

  ※ 設定画面→ホーム画面設定からライトテーマ、ダークテーマ、時間連動テーマの切り替えが可能です

### 📡 ライブタブ & ライブ視聴画面 (Live Player)
現在放送中のテレビ番組を、直感的な操作で快適に探して視聴するための機能群です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/3eb77f47-1847-4ac9-8a36-0000c88b0169" />

* **アプリ内PiP（ピクチャー・イン・ピクチャー）**: ライブ視聴中や録画再生中にリモコンの「戻るキー」を長押しすると小窓表示になり、番組を見ながら録画の検索や番組表の確認などのマルチタスクが可能です。
* **L字クロップ機能**: ニュース番組等のL字画面（情報テロップ）に合わせて、映像だけをクロップして画面いっぱいに拡大表示できます。
* **多様なバックエンド対応**: KonomiTV、Mirakurunに加え、EDCB（EMWUI経由のトランスコード再生・二画面同時視聴）にも完全対応しています。
* **サブチャンネル表示切替**: 設定からサブチャンネルの表示・非表示を簡単に切り替え可能です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/dce9c7be-019d-485f-91be-dae8223b7cce" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/94818de5-1f46-4134-84d8-947d66405dc4" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/9d65ef86-da41-4b62-bb5d-f052f71db976" />

* **直感的なザッピングUI**: 視聴を止めずに「下キー」でチャンネルのミニリストを展開し、「左右キー」でシームレスにチャンネル切り替えが可能です。「上キー」からは主/副音声、字幕、ストリーミング画質の変更が行えるサブメニューへアクセスできます。
* **実況コメントのリアルタイム同期**: KonomiTV等のAPIと連携し、専用エンジンでテレビ画面上にコメントを滑らかに描画します。
* **マニアックな信号情報表示**: 特定のキー操作で、現在の映像解像度、フレームレート、音声フォーマット等を画面左上に表示する「REGZA風オーバーレイ」を搭載しています。

### 🎬 ビデオタブ & 録画視聴画面 (Video Player)
録画した膨大な番組ライブラリから見たい番組を素早く見つけて再生するためのポータル画面です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/b339b4af-1d9c-41ed-8f39-95f76c8d9ecf" />

* **自動CMチャプタースキップ**: TvtPlayのチャプターファイルやKonomiTVのメタデータに含まれる「CM区間」を自動判定し、本編のみを連続再生します。
* **EDCB 直接アクセス & トランスコード視聴**: EDCB環境において、`api/xcode` を利用した柔軟な画質でのトランスコード再生や、シークが高速になる公開フォルダへの「直接アクセス」をサポートします。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/66341a78-96a4-49bd-9977-9d59853920fd" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/af6bbb3e-e210-49e7-9f6f-556e4e6d50c1" />

* **スマートスキップ機能**: 番組にチャプター情報が含まれているかどうかで、リモコン操作が自動的に最適化されます。
  * 短押し: 30秒スキップ / 10秒戻し
  * 長押し（チャプターあり）: 次/前のチャプターへ瞬時にジャンプ
  * 長押し（チャプターなし）: 3分スキップ / 1分戻し（CM飛ばしに最適）
* **シーンサーチ（チャプター一覧）**: 下キー長押しで画面下部にチャプターのサムネイル画像とタイムラインを一覧表示し、見たいシーンへ一気にジャンプ可能です。

### 🗂️ 録画リスト画面
数千〜数万件に及ぶ録画ライブラリを管理・検索する総合画面です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/320c8e2f-2230-4ef9-8102-06f29efcde63" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/32198e8b-676b-42a1-9aeb-aa6fa1698ee3" />

* **多角的なカテゴリ・フィルター**: 「未視聴」「シリーズ別」「ジャンル別」「曜日別」など、多様な切り口で瞬時に絞り込みが可能。
* **スマートキーワード自動予約**: 録画リストの単発番組のサブメニューから、直接「キーワード自動予約」の条件を手軽に作成できるようになりました。
* **フォーカス迷子の徹底排除**: 独自の `FocusTicketManager` と半透明オーバーレイ機構により、TV特有の「非同期描画と十字キー操作のズレ」を解決し、確実に元の位置へフォーカスが復帰します。

### 📁 ファイルライブラリ (SMB) 【NEW】
NASや共有フォルダ上のメディアファイルを直接再生できる新機能です。

* **SMBダイレクト再生**: SMB 上の動画ファイルを既存の ExoPlayer ベースの再生画面で扱えるようにし、録画再生と同じ操作体系でネットワーク越しのファイルを再生できます。
* **ハイブリッド・チャプター解析**: 動画と同じ階層にある `.chapter` (TvtPlay形式) や `.chapter.txt` (Amatsukaze形式) の外部チャプターを自動で読み込みます。MP4内部のチャプターとも自動で競合を解決し、シークバーに「CMスキップ帯」と「マーカー」を統合して表示します。
* **ピン留め機能**: よくアクセスするフォルダやファイルを「決定ボタン右メニュー」からピン留めし、一発で開けるショートカット機能を提供します。

### 📅 番組表タブ
テレビの大画面で一覧性が高く、サクサク動作する電子番組表（EPG）です。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/207c969a-5fbf-4cad-9cc2-74cbf2291a9a" />

* **高速・なめらかな描画エンジン**: Canvasを用いた独自の描画エンジンを採用し、数日分・数十チャンネルの巨大なグリッドでもカクつくことなくシームレスにスクロール可能です。
* **UIブラッシュアップ**: 左側の時間軸（時間バー）の背景色がテーマのベースカラーに馴染む合成色で描画され、ライトモードや時間連動テーマ適用時でもUIが美しく調和します。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/1e77cc0b-1715-4051-814b-facef6fdb9cf" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/01981537-a84c-4fd4-a785-e3273f3b3066" />

### ⏰ 録画予約タブ
市販のハイエンドレコーダーを凌駕する高度な予約管理システムです。

* EPGからのワンボタン予約に加え、除外キーワード、あいまい検索、重複回避（しない/同一/全チャンネル）、優先度設定、イベントリレー追従、録画後実行バッチの指定など、マニアックな条件をテレビ画面から直接設定できます。

<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/c6c434e6-d692-4a65-9703-90b235b77304" />
<img width="960" height="540" alt="Image" src="https://github.com/user-attachments/assets/abb75b5a-3a77-4938-8bce-73fdd9f7a14a" />

---

## 🛠 付属ツール
Komorebiの視聴体験を最大化するため、以下のツールを同梱しています。

* **Komorebi Configurator (`setup.sh` / Windows版ツール)**: EDCBでの「直接アクセス」に必要なシンボリックリンクやパス変換スクリプト(`resolver.lua`)を自動設定・更新します。
* **Komorebi Thumbnailer**: 録画ファイルからシークバー用のサムネイル画像（`.tile.webp`）を自動生成するツールです。

---

## ⚙️ 技術構成
最新のAndroid開発標準技術（Modern Android Development）を採用し、オールKotlin・Jetpack Composeで構築されています。

* **UI**: Compose for TV (TV Material3)
* **アーキテクチャ**: MVVM + Clean Architecture
* **DI**: Dagger Hilt
* **メディア再生**: Media3 (ExoPlayer) + FFmpeg Extension
* **コメント描画**: DanmakuFlameMaster (カスタム調整版)
* **バックグラウンド処理**: WorkManager

---

## ビルド方法

UI は Android のシステムフォントを使用します。字幕用の Kosugi Maru はリポジトリに同梱されているため、フォントを別途追加する必要はありません。

## ビルド前の準備

### 1. media-decoder-ffmpeg のセットアップ

FFmpeg デコーダーは以下の手順での準備が必要です。プロジェクトルート直下の `media/` に配置します（`.gitignore` に含まれています）。

#### 1.1 AndroidX Media3 のクローン

プロジェクトのルートディレクトリで実行してください。

```sh
git clone --branch 1.4.1 --depth 1 https://github.com/androidx/media.git media
```

#### 1.2 Media3 1.4.1 の不足ファイルを補完

Media3 1.4.1 にはいくつかのファイルが欠落しているため、スタブを作成します。

```sh
# datasource_httpengine ディレクトリが欠落しているため作成
mkdir -p media/libraries/datasource_httpengine

# 多くのモジュールで proguard-rules.txt が欠落しているため空ファイルを作成
for dir in media/libraries/*/; do
    [ ! -f "$dir/proguard-rules.txt" ] && touch "$dir/proguard-rules.txt"
done
```

#### 1.3 FFmpeg のクロスコンパイル

NDK のパスは環境によって異なります。Android Studio の場合は SDK Manager > SDK Tools > NDK (Side by side) からインストールでき、インストール先は以下が一般的です。

| OS | 一般的なパス |
|---|---|
| Linux | `~/Android/Sdk/ndk/<version>` |
| Mac | `~/Library/Android/sdk/ndk/<version>` |

コマンドラインで確認する場合:
```sh
ls ~/Android/Sdk/ndk/        # Linux
ls ~/Library/Android/sdk/ndk/  # Mac
```

```sh
# FFmpeg ソースを decoder_ffmpeg が参照する場所に直接クローン
git clone --depth 1 https://git.ffmpeg.org/ffmpeg.git media/libraries/decoder_ffmpeg/src/main/jni/ffmpeg

# クロスコンパイル（NDK_PATH は自身の環境に合わせて変更してください）
NDK_PATH=/path/to/ndk/<version>  # 例: ~/Android/Sdk/ndk/28.2.13676358
HOST_PLATFORM=$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)  # linux-x86_64 / darwin-x86_64 / darwin-arm64
MODULE_PATH=media/libraries/decoder_ffmpeg/src/main

bash "${MODULE_PATH}/jni/build_ffmpeg.sh" \
    "${MODULE_PATH}" \
    "${NDK_PATH}" \
    ${HOST_PLATFORM} \
    21 \
    vorbis opus flac mp3 ac3 eac3
```

---

## 🤝 SpecialThanks!
本アプリの開発にあたり、以下の素晴らしいプロジェクトと成果物を活用させていただいております。

* **[tsreadex](https://github.com/xtne6f/tsreadex)**: TS ストリーム解析および読み込み処理の基盤。
* **[libaribcaption](https://github.com/xqq/libaribcaption)**: ARIB STD-B24 字幕デコード処理の提供。
* **[KonomiTV](https://github.com/tsukumijima/KonomiTV)**: 強力な API バックエンドおよび配信プラットフォーム。
* **[Mirakurun](https://github.com/Chinachu/Mirakurun)**: チューナー管理および配信 API。
* **[DanmakuFlameMaster](https://github.com/bilibili/DanmakuFlameMaster)**: ニコニコ実況およびNX-Jikkyoのコメント表示。|-> 作者の方は逝去されています。長年の貢献に感謝し、心よりご冥福をお祈りいたします。R.I.P.
* **[Kosugi Maru](https://github.com/googlefonts/kosugi-maru)**: ARIB 字幕表示用の丸ゴシック体。


---
**Komorebi** の最新の進化をぜひお楽しみください！
さらなる改善案やバグ報告、特定のデバイスでの動作報告も、GitHub Issue や X にてお待ちしております。
