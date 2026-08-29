package com.google.ar.core.examples.java.helloar;

import android.graphics.Bitmap;
import android.util.Log;


import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.Queue;

public class ImgSocketThread extends Thread {

    private Socket imgSocket = null;
    private InputStream mInStream = null;
    private OutputStream mOutStream = null;

    private String mIP;
    private int mPort = 12345;

    ImgSocketThread(String ipServer, int portServer){
        mIP = ipServer;
        mPort = portServer;
    }


    public int sendPoseToServer(Pose pose){
        if(imgSocket == null){
            return -1;
        }
        if(!imgSocket.isConnected()){
            return -2;
        }
        float[] poseTranslation = pose.getTranslation();
        float[] poseQuaternion = pose.getRotationQuaternion();
        String strMessage = String.format("%f;%f;%f;%f;%f;%f;%f",
                poseTranslation[0], poseTranslation[1], poseTranslation[2],
                poseQuaternion[0], poseQuaternion[1], poseQuaternion[2], poseQuaternion[3]);
        //Translation X Y Z, Quaternion element 1 2 3 4
        int length = strMessage.length();
        String strHead = String.format("1;%-16d", length);
        //1 represents the type of message is pose
        PrintWriter pwPoseData = new PrintWriter(mOutStream);

        pwPoseData.write(strHead);
        pwPoseData.flush();
        pwPoseData.write(strMessage);
        pwPoseData.flush();
        return 0;
    }

    public int sendPointsToServer(PointCloud pointCloud) throws IOException {
        if(imgSocket == null){
            return -1;
        }
        if(!imgSocket.isConnected()){
            return -2;
        }

        FloatBuffer bufCloud = pointCloud.getPoints();  //4 consecutive float numbers describes one point
        int lenCloud = bufCloud.limit();
        float[] arrayCloud = new float[lenCloud];
        bufCloud.get(arrayCloud);

        int length = lenCloud * 4;  //float variable covers 4 bytes

        ByteBuffer bbufCloud = ByteBuffer.allocate(length);
        for (Float fltPoint : arrayCloud) {
            bbufCloud.putFloat(fltPoint);
        }

        String strHead = String.format("2;%-16d", length);
        //1 represents the type of message is pose
        PrintWriter pwPoseData = new PrintWriter(mOutStream);

        pwPoseData.write(strHead);
        pwPoseData.flush();
        try {
            mOutStream.write(bbufCloud.array());
            mOutStream.flush();
        }catch (IOException e){
            e.printStackTrace();
        }

        return 0;
    }

    @Override
    public void run(){

        try{
            imgSocket = new Socket(mIP, mPort);
            mInStream = imgSocket.getInputStream();
            mOutStream = imgSocket.getOutputStream();
            if(imgSocket.isConnected()){
                Log.i("ImgSocketThread", "Server connected.");
            }else{
                Log.e("ImgSocketThread", "Server connected failed");
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}
