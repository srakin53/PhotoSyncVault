# PhotoSyncVault

Ứng dụng Android đóng gói sẵn ảnh trong file APK để lưu toàn bộ vào Thư viện (Gallery), đồng thời tự động đồng bộ ảnh mới từ link `manifest.json` khi có mạng.

## 1. Cập nhật ảnh sẵn trong APK (Offline)
1. Copy ảnh (`.jpg`, `.jpeg`, `.png`, `.webp`) vào `app/src/main/assets/photos/`.
2. Build lại APK:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   File APK nằm tại `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Tự động đồng bộ ảnh mới khi có mạng (GitHub Pages)
1. Đưa project này lên **1 repo GitHub (Public)**.
2. Vào **Settings -> Pages -> Build and deployment -> Source**, chọn **GitHub Actions**.
3. Mỗi lần bạn kéo-thả/upload ảnh mới vào thư mục `app/src/main/assets/photos/` trên GitHub:
   - GitHub Actions sẽ tự động tạo file `manifest.json` và xuất bản lên:
     `https://<username>.github.io/<ten-repo>/manifest.json`
   - Đồng thời tự build sẵn một bản `PhotoSyncVault-debug-apk` mới trong tab **Actions**.
4. Dán link `https://<username>.github.io/<ten-repo>/manifest.json` vào app (hoặc điền vào `default_sync_url` trong `app/src/main/res/values/strings.xml` rồi build lại APK để gắn cứng mặc định).
