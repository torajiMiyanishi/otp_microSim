import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jp.soars.core.TAgent;
import jp.soars.core.TAgentManager;
import jp.soars.core.TRuleExecutor;
import jp.soars.core.TSOARSBuilder;
import jp.soars.core.TSpot;
import jp.soars.core.TSpotManager;
import jp.soars.core.enums.ERuleDebugMode;
import jp.soars.utils.csv.TCCsvData;
import jp.soars.utils.random.ICRandom;

// 以下可視化用ライブラリ
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.Random;
import java.util.HashMap;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.util.Random;


/**
 * メインクラス
 * @author nagakane
 */
public class Main {

    public static void main(String[] args) throws IOException {





        /** directoryの指定 */
        String path_to_zenrin = "inputData\\zenrin_building.csv"; // 目的地（新潟県妙高市）となる建物名と緯度経度が入っているテーブル
        String filePath = "log\\log.csv"; // log.csvの書き出し先
        String pathToPbf = "inputData\\Myoko.osm.pbf"; // 妙高市のosmデータ
        String dirToGtfs = "inputData\\"; // 妙高市を走行する路線バス・コミュニティバスのGTFSデータが入っているディレクトリ

        // 目的地の候補地のcsvを読み込み
        TCCsvData csvZenrin = new TCCsvData();
        csvZenrin.readFrom(path_to_zenrin);

        // otpの初期化
        OtpRouting otpRouting = new OtpRouting();
        otpRouting.initializeOtp(pathToPbf,dirToGtfs);

        // シミュレーションのパラメータ 24時間以内のみ考慮しています。
        LocalTime startTime = LocalTime.of(4, 0); // 開始時刻
        LocalTime endTime = LocalTime.of(21, 0); // 終了時刻
        int numOfAgents = 100;



        Random random = new Random();

        // logger
        PrintWriter writer = new PrintWriter(new FileWriter(filePath));
        writer.printf("agentId,status,latlonHash,startTime,endTime,latlonStay\n");

        for (int agentId=0; agentId<numOfAgents; agentId++){
            System.out.println("agentId is @"+agentId);

            // シミュレーションにおける状態管理変数
            LocalTime endTimeOfStaying = startTime;
            int currentBuildingOfRecordIndex = random.nextInt(csvZenrin.getNoOfRows());
            HashMap<String,String> row = csvZenrin.getRow(currentBuildingOfRecordIndex);
            double currentLat = Double.parseDouble(row.get("緯度"));
            double currentLon = Double.parseDouble(row.get("経度"));
            String currentName = row.get("建物名");

            // 指定された時間内でシミュレーションを実行
            for (LocalTime time = startTime; time.isBefore(endTime.plusMinutes(1)); time = time.plusMinutes(1)) {
                if(time.equals(endTimeOfStaying)){ // stay終了時刻に達したら
                    System.out.println("--------------------CurrentTime@"+time+"--------------------agent is @"+currentName);
                    // ランダムでゼンリン建物ポイントデータを取得
                    int recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                    while (currentBuildingOfRecordIndex == recordIndex){
                        recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                    }
                    row = csvZenrin.getRow(recordIndex);
                    double lat  = Double.parseDouble(row.get("緯度"));
                    double lon  = Double.parseDouble(row.get("経度"));
                    String name = row.get("建物名");
                    // 至近すぎる二点の場合は却下
                    while (otpRouting.haversineDistance(currentLat,currentLon,lat,lon) < 0.3) { // 直線距離300m以下は却下
                        // ランダムでゼンリン建物ポイントデータを取得
                        recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                        while (currentBuildingOfRecordIndex == recordIndex){
                            recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                        }
                        row = csvZenrin.getRow(recordIndex);
                        lat  = Double.parseDouble(row.get("緯度"));
                        lon  = Double.parseDouble(row.get("経度"));
                        name = row.get("建物名");
                    }
                    // run otp
                    OtpRouting.Result result;
                    result = otpRouting.routingRequest(time.getHour(),time.getMinute(),currentLat,currentLon,lat,lon);
                    while(!result.getIsSuccessed()){ // routingが帰ってくるまでランダムに行き先を決定してroutingに投げ続ける。
                        // ランダムでゼンリン建物ポイントデータを取得
                        recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                        while (currentBuildingOfRecordIndex == recordIndex){
                            recordIndex = random.nextInt(csvZenrin.getNoOfRows());
                        }
                        row = csvZenrin.getRow(recordIndex);
                        lat  = Double.parseDouble(row.get("緯度"));
                        lon  = Double.parseDouble(row.get("経度"));
                        name = row.get("建物名");
                        result = otpRouting.routingRequest(time.getHour(),time.getMinute(),currentLat,currentLon,lat,lon);
                    }
                    int timeStaying = random.nextInt(10,60);
                    LocalTime endTripTime = result.getLocalTime();
//                    writer.printf(String.valueOf(agentId)+",STAY,,"+endTripTime.toString()+","+endTripTime.plusMinutes(timeStaying).toString()+","+String.valueOf(lat)+"@"+String.valueOf(lon)+"\n");

                    // 状況を更新
                    endTimeOfStaying = endTripTime.plusMinutes(timeStaying);
                    currentLat = lat;
                    currentLon = lon;
                    currentName = name;

                    // write log
                    for (String record: result.getStringList()){
                        writer.printf(String.valueOf(agentId)+","+record+"\n");
                    }
                }
            }
        }
        writer.close();


        /** 以下otpのテスト */


//        otpRouting.initializeOtp(pathToPbf, dirToGtfs);
    }
}