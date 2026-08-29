**INTRODUCTION**

This demo running on Android mobile phone is a simple realization of sensing environment and creating a point cloud via PCL library. 

**Environment** 

Android 10+

**Requirements**:

**Android Package:**

ARCore

**Run**

Switch the target mobile phone into "Developer Mode"

In Android Studio, click "Run"

**Modify**

We can change the server IP address and port in

"ar_navigation_pcl\app\src\main\java\com\google\ar\core\examples\java\helloar\HelloArActivity.java"

```java
199    socketThread = new ImgSocketThread("192.168.1.20", 12345);
```

In order to embed ARCore function into a program, one can follow the instruction here:

[在 Android 应用中启用 AR  | ARCore  | Google for Developers](https://developers.google.cn/ar/develop/java/enable-arcore?hl=zh-cn)

