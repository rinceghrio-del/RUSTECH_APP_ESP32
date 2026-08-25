# Kulang na model files (idol, ilagay mo ito dito sa assets/)

Gagana ang app KAHIT WALA muna ito (RoboEyes lang lalabas, walang mag-a-auto camera), pero
hindi magsisimula ang person detection / face recognition hangga't wala ang dalawang file
na ito sa parehong folder na ito (`app/src/main/assets/`):

## 1. yolo_person.tflite
Ginagamit para malaman kung MAY TAO ba sa camera (para lumipat mula RoboEyes papunta sa camera view).

Paano kumuha (pinakamadali - YOLOv8n, COCO 80 classes):
```
pip install ultralytics
yolo export model=yolov8n.pt format=tflite imgsz=320
```
Kukunin mo yung `yolov8n_saved_model/yolov8n_float16.tflite`, papalitan ng pangalan
na `yolo_person.tflite`, ilagay dito.

Kung ibang imgsz ang ginamit mo sa export, i-update din ang `INPUT_SIZE` sa
`YoloPersonDetector.kt` para tugma.

## 2. face_embedder.tflite
Ginagamit para malaman kung SINO ang taong nakita (face recognition, hindi lang detection).
Kailangan ng MobileFaceNet-style model: 112x112 input, ~192-dim na output embedding.

Maghanap ng "MobileFaceNet.tflite" o "mobile_face_net.tflite" - maraming open-source
Android face-recognition sample project sa GitHub na may kasamang ready-to-use na
`.tflite` file nito. I-download, palitan ng pangalan na `face_embedder.tflite`, ilagay dito.

---

Kapag nasa lugar na ang dalawang file, i-push mo lang sa GitHub (kasama ng ibang code) at
gagana na ang GitHub Actions build mo tulad ng dati - kasama na sila sa APK.
