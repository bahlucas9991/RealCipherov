package com.devicespooflab.hooks.data;

import android.content.Context;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;

import com.devicespooflab.hooks.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class DevicePresetCatalog {

    private static final String CACHE_NAME = "remote_device_presets.json";
    private static final String CACHE_KEY_SOURCE_URL = "sourceUrl";
    private static final String CACHE_KEY_UPDATED_AT = "updatedAt";
    private static final String CACHE_KEY_PRESETS = "presets";
    private static final String CURRENT_DEVICE_PRESET_ID = "current_device";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    public List<DevicePreset> load(Context context) {
        List<DevicePreset> cachedPresets = loadFromCache(context);
        if (!cachedPresets.isEmpty()) {
            return withCurrentDevicePreset(context, cachedPresets);
        }
        List<DevicePreset> remote = refreshRemote(context, AppSettingsStore.getPresetSourceUrl(context));
        if (!remote.isEmpty()) {
            return withCurrentDevicePreset(context, remote);
        }
        return withCurrentDevicePreset(context, getBuiltInPresets());
    }

    public List<DevicePreset> refreshRemote(Context context, String sourceUrl) {
        try {
            JSONArray normalizedArray = fetchRemotePresetArray(sourceUrl);
            List<DevicePreset> presets = parsePresetArray(normalizedArray);
            if (!presets.isEmpty()) {
                writeCache(context, sourceUrl, normalizedArray);
                return withCurrentDevicePreset(context, presets);
            }
        } catch (Exception ignored) {
        }
        return withCurrentDevicePreset(context, loadFromCache(context));
    }

    private List<DevicePreset> loadFromCache(Context context) {
        File cacheFile = getCacheFile(context);
        if (!cacheFile.exists()) {
            return Collections.emptyList();
        }
        try (InputStream inputStream = new FileInputStream(cacheFile)) {
            JSONArray array = extractPresetArray(readFully(inputStream));
            return parsePresetArray(array);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void writeCache(Context context, String sourceUrl, JSONArray array) {
        try (FileOutputStream outputStream = new FileOutputStream(getCacheFile(context), false)) {
            JSONObject payload = new JSONObject();
            payload.put(CACHE_KEY_SOURCE_URL, sourceUrl == null ? "" : sourceUrl);
            payload.put(CACHE_KEY_UPDATED_AT, System.currentTimeMillis());
            payload.put(CACHE_KEY_PRESETS, array);
            outputStream.write(payload.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private File getCacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_NAME);
    }

    private List<DevicePreset> withCurrentDevicePreset(Context context, List<DevicePreset> source) {
        ArrayList<DevicePreset> merged = new ArrayList<>();
        merged.add(createCurrentDevicePreset(context));
        if (source != null) {
            for (DevicePreset preset : source) {
                if (preset == null || CURRENT_DEVICE_PRESET_ID.equals(preset.getId())) {
                    continue;
                }
                merged.add(preset);
            }
        }
        return merged;
    }

    private DevicePreset createCurrentDevicePreset(Context context) {
        DeviceProfile profile = new DeviceProfile();
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();

        profile.setBrand(Build.BRAND);
        profile.setManufacturer(Build.MANUFACTURER);
        profile.setModel(Build.MODEL);
        profile.setProductName(Build.PRODUCT);
        profile.setDeviceCode(Build.DEVICE);
        profile.setBoard(Build.BOARD);
        profile.setHardware(Build.HARDWARE);
        profile.setBuildFingerprint(Build.FINGERPRINT);
        profile.setBuildId(Build.ID);
        profile.setBuildDisplayId(Build.DISPLAY);
        profile.setBuildIncremental(Build.VERSION.INCREMENTAL);
        profile.setBuildRelease(Build.VERSION.RELEASE);
        profile.setBuildSdk(Build.VERSION.SDK_INT);
        profile.setBuildDescription(Build.TYPE);
        profile.setBuildFlavor(Build.TYPE);
        profile.setBuildProduct(Build.PRODUCT);
        profile.setBuildCharacteristics(isTablet(metrics) ? "tablet" : "nosdcard");
        profile.setScreenWidth(metrics.widthPixels);
        profile.setScreenHeight(metrics.heightPixels);
        profile.setScreenDensity(metrics.densityDpi);
        profile.setTimezone(TimeZone.getDefault().getID());
        profile.setUserAgent(buildDefaultUserAgent(profile));
        profile.setBootloader(Build.BOOTLOADER);
        profile.setCpuAbi(Build.CPU_ABI);
        profile.setCpuAbiList(joinAbis(Build.SUPPORTED_ABIS));
        profile.setCpuAbiList64(joinAbis(Build.SUPPORTED_64_BIT_ABIS));
        profile.setCpuAbiList32(joinAbis(Build.SUPPORTED_32_BIT_ABIS));
        profile.setSocModel(readBuildField("SOC_MODEL"));
        profile.setSocManufacturer(readBuildField("SOC_MANUFACTURER"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            profile.setSecurityPatch(Build.VERSION.SECURITY_PATCH);
        }

        try {
            TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
            if (telephonyManager != null) {
                profile.setOperatorAlpha(telephonyManager.getNetworkOperatorName());
                profile.setOperatorNumeric(telephonyManager.getNetworkOperator());
                profile.setSimOperatorAlpha(telephonyManager.getSimOperatorName());
                profile.setSimOperatorNumeric(telephonyManager.getSimOperator());
                profile.setSimCountryIso(telephonyManager.getSimCountryIso());
            }
        } catch (Throwable ignored) {
        }

        profile.applyFallbacks();
        return new DevicePreset(
            CURRENT_DEVICE_PRESET_ID,
            context.getString(R.string.preset_current_device_brand),
            firstNonBlank(profile.getModel(), context.getString(R.string.preset_current_device_model)),
            context.getString(R.string.preset_current_device_summary),
            profile
        );
    }

    private List<DevicePreset> getBuiltInPresets() {
        List<DevicePreset> list = new ArrayList<>();
        list.add(new DevicePreset("samsung_s25u", "Samsung", "Galaxy S25 Ultra", "Snapdragon 8 Elite - Android 15", makeProfile(
            "Samsung","Samsung","SM-S938B","q6c","q6cxxx",
            "samsung/q6c/q6cxxx:15/AP4A.250205.002/BUU3CXK1:user/release-keys",
            "AP4A.250205.002","15",35,"2025-03-01",1440,3120,513,"Snapdragon 8 Elite","Snapdragon")));
        list.add(new DevicePreset("samsung_s25", "Samsung", "Galaxy S25", "Exynos 2500 - Android 15", makeProfile(
            "Samsung","Samsung","SM-S931B","s10","s10x",
            "samsung/s10/s10x:15/AP4A.250205.002/XXX:user/release-keys",
            "AP4A.250205.002","15",35,"2025-03-01",1080,2340,480,"Exynos 2500","Samsung")));
        list.add(new DevicePreset("samsung_s24u", "Samsung", "Galaxy S24 Ultra", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S928B","e3q","e3qxxx",
            "samsung/e3q/e3qxxx:14/UP1A.231005.007/S928BXXS1AXA7:user/release-keys",
            "UP1A.231005.007","14",34,"2024-11-01",1440,3120,513,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("samsung_s24", "Samsung", "Galaxy S24", "Exynos 2400 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S921B","s9","s9x",
            "samsung/s9/s9x:14/UP1A.231005.007/S921BXXS1AXA5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-11-01",1080,2340,480,"Exynos 2400","Samsung")));
        list.add(new DevicePreset("samsung_s23u", "Samsung", "Galaxy S23 Ultra", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S918B","dm3q","dm3qxxx",
            "samsung/dm3q/dm3qxxx:14/UP1A.231005.007/S918BXXU1BWL7:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1440,3088,513,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("samsung_s23", "Samsung", "Galaxy S23", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S911B","dm2q","dm2qxxx",
            "samsung/dm2q/dm2qxxx:14/UP1A.231005.007/S911BXXU1BWL7:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1080,2340,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("samsung_s22u", "Samsung", "Galaxy S22 Ultra", "Snapdragon 8 Gen 1 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S908B","d2q","d2qxxx",
            "samsung/d2q/d2qxxx:14/UP1A.231005.007/S908BXXU1BXI5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-07-01",1440,3088,500,"Snapdragon 8 Gen 1","Qualcomm")));
        list.add(new DevicePreset("samsung_s22", "Samsung", "Galaxy S22", "Exynos 2200 - Android 14", makeProfile(
            "Samsung","Samsung","SM-S901B","d1x","d1x",
            "samsung/d1x/d1x:14/UP1A.231005.007/S901BXXU1BXI5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-07-01",1080,2340,480,"Exynos 2200","Samsung")));
        list.add(new DevicePreset("samsung_s21u", "Samsung", "Galaxy S21 Ultra", "Exynos 2100 - Android 14", makeProfile(
            "Samsung","Samsung","SM-G998B","o3q","o3qxxx",
            "samsung/o3q/o3qxxx:14/UP1A.231005.007/G998BXXU1BXI3:user/release-keys",
            "UP1A.231005.007","14",34,"2024-05-01",1440,3200,515,"Exynos 2100","Samsung")));
        list.add(new DevicePreset("samsung_s21", "Samsung", "Galaxy S21", "Exynos 2100 - Android 14", makeProfile(
            "Samsung","Samsung","SM-G991B","o1q","o1q",
            "samsung/o1q/o1q:14/UP1A.231005.007/G991BXXU1BXI3:user/release-keys",
            "UP1A.231005.007","14",34,"2024-05-01",1080,2400,480,"Exynos 2100","Samsung")));
        list.add(new DevicePreset("samsung_zfold6", "Samsung", "Galaxy Z Fold 6", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Samsung","Samsung","SM-F956B","q6q","q6qxxx",
            "samsung/q6q/q6qxxx:14/UP1A.231005.007/F956BXXU1AXB5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-12-01",1856,2160,420,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("samsung_zfold5", "Samsung", "Galaxy Z Fold 5", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Samsung","Samsung","SM-F946B","q4q","q4qxxx",
            "samsung/q4q/q4qxxx:14/UP1A.231005.007/F946BXXU1BWL7:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1812,2176,420,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("samsung_zflip6", "Samsung", "Galaxy Z Flip 6", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Samsung","Samsung","SM-F741B","b6q","b6qxxx",
            "samsung/b6q/b6qxxx:14/UP1A.231005.007/F741BXXU1AXB5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-12-01",1080,2640,480,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("samsung_zflip5", "Samsung", "Galaxy Z Flip 5", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Samsung","Samsung","SM-F731B","b4q","b4qxxx",
            "samsung/b4q/b4qxxx:14/UP1A.231005.007/F731BXXU1BWL7:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1080,2640,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("samsung_note20u", "Samsung", "Galaxy Note 20 Ultra", "Exynos 990 - Android 13", makeProfile(
            "Samsung","Samsung","SM-N986B","c2q","c2q",
            "samsung/c2q/c2q:13/TP1A.220624.014/N986BXXU1BXI1:user/release-keys",
            "TP1A.220624.014","13",33,"2023-09-01",1440,3088,500,"Exynos 990","Samsung")));
        list.add(new DevicePreset("samsung_a55", "Samsung", "Galaxy A55", "Exynos 1480 - Android 14", makeProfile(
            "Samsung","Samsung","SM-A556B","a55x","a55x",
            "samsung/a55x/a55x:14/UP1A.231005.007/A556BXXU1AXA5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-11-01",1080,2340,480,"Exynos 1480","Samsung")));
        list.add(new DevicePreset("google_pixel9proxl", "Google", "Pixel 9 Pro XL", "Tensor G4 - Android 15", makeProfile(
            "Google","Google","Pixel 9 Pro XL","komodo","komodo",
            "google/komodo/komodo:15/AP4A.250205.002/12440983:user/release-keys",
            "AP4A.250205.002","15",35,"2025-03-01",1344,2992,490,"Tensor G4","Google")));
        list.add(new DevicePreset("google_pixel9pro", "Google", "Pixel 9 Pro", "Tensor G4 - Android 15", makeProfile(
            "Google","Google","Pixel 9 Pro","caiman","caiman",
            "google/caiman/caiman:15/AP4A.250205.002/12440983:user/release-keys",
            "AP4A.250205.002","15",35,"2025-03-01",1280,2856,490,"Tensor G4","Google")));
        list.add(new DevicePreset("google_pixel9", "Google", "Pixel 9", "Tensor G4 - Android 15", makeProfile(
            "Google","Google","Pixel 9","tokay","tokay",
            "google/tokay/tokay:15/AP4A.250205.002/12440983:user/release-keys",
            "AP4A.250205.002","15",35,"2025-03-01",1080,2424,480,"Tensor G4","Google")));
        list.add(new DevicePreset("google_pixel8pro", "Google", "Pixel 8 Pro", "Tensor G3 - Android 14", makeProfile(
            "Google","Google","Pixel 8 Pro","husky","husky",
            "google/husky/husky:14/AP1A.240405.002/11277257:user/release-keys",
            "AP1A.240405.002","14",34,"2024-10-01",1344,2992,490,"Tensor G3","Google")));
        list.add(new DevicePreset("google_pixel8", "Google", "Pixel 8", "Tensor G3 - Android 14", makeProfile(
            "Google","Google","Pixel 8","shiba","shiba",
            "google/shiba/shiba:14/AP1A.240405.002/11277257:user/release-keys",
            "AP1A.240405.002","14",34,"2024-10-01",1080,2400,480,"Tensor G3","Google")));
        list.add(new DevicePreset("google_pixel7pro", "Google", "Pixel 7 Pro", "Tensor G2 - Android 14", makeProfile(
            "Google","Google","Pixel 7 Pro","cheetah","cheetah",
            "google/cheetah/cheetah:14/AP1A.240305.019/11122619:user/release-keys",
            "AP1A.240305.019","14",34,"2024-07-01",1440,3120,480,"Tensor G2","Google")));
        list.add(new DevicePreset("google_pixel7", "Google", "Pixel 7", "Tensor G2 - Android 14", makeProfile(
            "Google","Google","Pixel 7","panther","panther",
            "google/panther/panther:14/AP1A.240305.019/11122619:user/release-keys",
            "AP1A.240305.019","14",34,"2024-07-01",1080,2400,480,"Tensor G2","Google")));
        list.add(new DevicePreset("google_pixel6pro", "Google", "Pixel 6 Pro", "Tensor - Android 14", makeProfile(
            "Google","Google","Pixel 6 Pro","raven","raven",
            "google/raven/raven:14/AP1A.240305.019/11063078:user/release-keys",
            "AP1A.240305.019","14",34,"2024-05-01",1440,3120,480,"Tensor","Google")));
        list.add(new DevicePreset("google_pixel6", "Google", "Pixel 6", "Tensor - Android 14", makeProfile(
            "Google","Google","Pixel 6","oriole","oriole",
            "google/oriole/oriole:14/AP1A.240305.019/11063078:user/release-keys",
            "AP1A.240305.019","14",34,"2024-05-01",1080,2400,480,"Tensor","Google")));
        list.add(new DevicePreset("oneplus_13", "OnePlus", "OnePlus 13", "Snapdragon 8 Elite - Android 15", makeProfile(
            "OnePlus","OnePlus","CPH2653","pineapple","OP5983",
            "OnePlus/OP5983/CPH2653:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-02-01",1440,3168,510,"Snapdragon 8 Elite","Qualcomm")));
        list.add(new DevicePreset("oneplus_12", "OnePlus", "OnePlus 12", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "OnePlus","OnePlus","CPH2581","pineapple","OP598E",
            "OnePlus/OP598E/CPH2581:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-10-01",1440,3168,510,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("oneplus_11", "OnePlus", "OnePlus 11", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "OnePlus","OnePlus","CPH2447","salami","OP5941",
            "OnePlus/OP5941/CPH2447:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1440,3216,525,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("oneplus_10pro", "OnePlus", "OnePlus 10 Pro", "Snapdragon 8 Gen 1 - Android 13", makeProfile(
            "OnePlus","OnePlus","NE2215","lemonadep","OP5551",
            "OnePlus/OP5551/NE2215:13/TP1A.220624.014/xxx:user/release-keys",
            "TP1A.220624.014","13",33,"2023-12-01",1440,3216,525,"Snapdragon 8 Gen 1","Qualcomm")));
        list.add(new DevicePreset("oneplus_9pro", "OnePlus", "OnePlus 9 Pro", "Snapdragon 888 - Android 13", makeProfile(
            "OnePlus","OnePlus","LE2123","lemonadep","OP5551",
            "OnePlus/OP5551/LE2123:13/TP1A.220624.014/xxx:user/release-keys",
            "TP1A.220624.014","13",33,"2023-09-01",1440,3216,525,"Snapdragon 888","Qualcomm")));
        list.add(new DevicePreset("xiaomi_15pro", "Xiaomi", "Xiaomi 15 Pro", "Snapdragon 8 Elite - Android 15", makeProfile(
            "Xiaomi","Xiaomi","24101PNB7C","shennong","shennong",
            "Xiaomi/shennong/shennong:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-03-01",1440,3200,522,"Snapdragon 8 Elite","Qualcomm")));
        list.add(new DevicePreset("xiaomi_14u", "Xiaomi", "Xiaomi 14 Ultra", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Xiaomi","Xiaomi","24030PN60G","aurora","aurora",
            "Xiaomi/aurora/aurora:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-10-01",1440,3200,522,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("xiaomi_14", "Xiaomi", "Xiaomi 14", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Xiaomi","Xiaomi","23127PN0CG","shennong","fuxi",
            "Xiaomi/fuxi/fuxi:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1440,3200,522,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("xiaomi_13u", "Xiaomi", "Xiaomi 13 Ultra", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Xiaomi","Xiaomi","2304FPN6DC","ishtar","ishtar",
            "Xiaomi/ishtar/ishtar:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-06-01",1440,3200,522,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("xiaomi_13pro", "Xiaomi", "Xiaomi 13 Pro", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Xiaomi","Xiaomi","2210132G","nuwa","nuwa",
            "Xiaomi/nuwa/nuwa:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-06-01",1440,3200,522,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("xiaomi_13", "Xiaomi", "Xiaomi 13", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Xiaomi","Xiaomi","2211133C","fuxi","fuxi",
            "Xiaomi/fuxi/fuxi:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-06-01",1080,2400,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("poco_f6", "POCO", "POCO F6", "Snapdragon 8s Gen 3 - Android 14", makeProfile(
            "POCO","Xiaomi","24069PC21G","peridot","peridot",
            "POCO/peridot/peridot:14/UP1A.231005.007/OS1.0.5.0:user/release-keys",
            "UP1A.231005.007","14",34,"2024-11-01",1220,2712,480,"Snapdragon 8s Gen 3","Qualcomm")));
        list.add(new DevicePreset("poco_f5", "POCO", "POCO F5", "Snapdragon 7+ Gen 2 - Android 14", makeProfile(
            "POCO","Xiaomi","23049PCD8G","marble","marble",
            "POCO/marble/marble:14/UP1A.231005.007/OS1.0.4.0:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1080,2400,480,"Snapdragon 7+ Gen 2","Qualcomm")));
        list.add(new DevicePreset("poco_x6pro", "POCO", "POCO X6 Pro", "Dimensity 8300 Ultra - Android 14", makeProfile(
            "POCO","Xiaomi","2311DRK48G","duchamp","duchamp",
            "POCO/duchamp/duchamp:14/UP1A.231005.007/OS1.0.3.0:user/release-keys",
            "UP1A.231005.007","14",34,"2024-07-01",1220,2712,480,"Dimensity 8300 Ultra","MediaTek")));
        list.add(new DevicePreset("realme_gt7pro", "realme", "realme GT 7 Pro", "Snapdragon 8 Elite - Android 15", makeProfile(
            "realme","realme","RMX5010","raven","RMX5010",
            "realme/RMX5010/RMX5010:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-03-01",1264,2780,480,"Snapdragon 8 Elite","Qualcomm")));
        list.add(new DevicePreset("realme_gt6", "realme", "realme GT 6", "Snapdragon 8s Gen 3 - Android 14", makeProfile(
            "realme","realme","RMX3852","sweet","RMX3852",
            "realme/RMX3852/RMX3852:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-10-01",1264,2780,480,"Snapdragon 8s Gen 3","Qualcomm")));
        list.add(new DevicePreset("realme_gt5pro", "realme", "realme GT 5 Pro", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "realme","realme","RMX3888","moon","RMX3888",
            "realme/RMX3888/RMX3888:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1264,2780,480,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("oppo_findx8pro", "OPPO", "OPPO Find X8 Pro", "Dimensity 9400 - Android 15", makeProfile(
            "OPPO","OPPO","CPH2659","pineapple","OP5983",
            "OPPO/OP5983/CPH2659:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-02-01",1440,3168,522,"Dimensity 9400","MediaTek")));
        list.add(new DevicePreset("oppo_findx7u", "OPPO", "OPPO Find X7 Ultra", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "OPPO","OPPO","PHY110","pineapple","OP5983",
            "OPPO/OP5983/PHY110:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1440,3168,522,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("vivo_x200pro", "vivo", "vivo X200 Pro", "Dimensity 9400 - Android 15", makeProfile(
            "vivo","vivo","V2405","PD2405","PD2405",
            "vivo/PD2405/V2405:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-03-01",1440,3168,510,"Dimensity 9400","MediaTek")));
        list.add(new DevicePreset("vivo_x100pro", "vivo", "vivo X100 Pro", "Dimensity 9300 - Android 14", makeProfile(
            "vivo","vivo","V2324","PD2324","PD2324",
            "vivo/PD2324/V2324:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1440,3168,510,"Dimensity 9300","MediaTek")));
        list.add(new DevicePreset("honor_magic7pro", "Honor", "Honor Magic 7 Pro", "Snapdragon 8 Elite - Android 15", makeProfile(
            "Honor","Honor","HSP-AN00","napoli","HSP",
            "Honor/HSP/HSP-AN00:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-03-01",1344,2992,490,"Snapdragon 8 Elite","Qualcomm")));
        list.add(new DevicePreset("honor_magic6pro", "Honor", "Honor Magic 6 Pro", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Honor","Honor","BVL-AN16","bora","BVL",
            "Honor/BVL/BVL-AN16:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-10-01",1344,2992,490,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("huawei_pura70u", "Huawei", "HUAWEI Pura 70 Ultra", "Kirin 9010 - HarmonyOS 4.2", makeProfile(
            "HUAWEI","HUAWEI","HUAWEI Pura 70 Ultra","cld","cld",
            "HUAWEI/cld/cld:14/HarmonyOS4.2.0/xxx:user/release-keys",
            "HarmonyOS4.2.0","14",34,"2024-12-01",1440,3168,510,"Kirin 9010","HiSilicon")));
        list.add(new DevicePreset("huawei_mate60pro", "Huawei", "HUAWEI Mate 60 Pro", "Kirin 9000S - HarmonyOS 4.0", makeProfile(
            "HUAWEI","HUAWEI","HUAWEI Mate 60 Pro","walden","walden",
            "HUAWEI/walden/walden:13/HarmonyOS4.0.0/xxx:user/release-keys",
            "HarmonyOS4.0.0","13",33,"2024-06-01",1440,3120,510,"Kirin 9000S","HiSilicon")));
        list.add(new DevicePreset("motorola_edge50u", "Motorola", "moto edge 50 ultra", "Snapdragon 8s Gen 3 - Android 14", makeProfile(
            "Motorola","Motorola","XT2401-1","canyon","canyon",
            "Motorola/canyon/canyon:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-10-01",1440,3120,480,"Snapdragon 8s Gen 3","Qualcomm")));
        list.add(new DevicePreset("motorola_edge40pro", "Motorola", "moto edge 40 pro", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Motorola","Motorola","XT2301-4","tundra","tundra",
            "Motorola/tundra/tundra:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-07-01",1440,3120,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("asus_rogphone9", "ASUS", "ROG Phone 9", "Snapdragon 8 Elite - Android 15", makeProfile(
            "ASUS","ASUS","AI2501A","AI2501","AI2501",
            "ASUS/AI2501/AI2501A:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-02-01",1080,2400,480,"Snapdragon 8 Elite","Qualcomm")));
        list.add(new DevicePreset("asus_rogphone8", "ASUS", "ROG Phone 8", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "ASUS","ASUS","AI2401A","AI2401","AI2401",
            "ASUS/AI2401/AI2401A:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1080,2400,480,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("asus_zenfone11u", "ASUS", "Zenfone 11 Ultra", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "ASUS","ASUS","AI2401","AI2401","AI2401",
            "ASUS/AI2401/AI2401:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1080,2400,480,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("sony_xperia1vi", "Sony", "Xperia 1 VI", "Snapdragon 8 Gen 3 - Android 14", makeProfile(
            "Sony","Sony","XQ-EC72","pdx256","PDX256",
            "Sony/PDX256/XQ-EC72:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1080,2340,480,"Snapdragon 8 Gen 3","Qualcomm")));
        list.add(new DevicePreset("sony_xperia5v", "Sony", "Xperia 5 V", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Sony","Sony","XQ-DE72","pdx236","PDX236",
            "Sony/PDX236/XQ-DE72:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-06-01",1080,2520,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("nothing_phone3a", "Nothing", "Phone (3a)", "Snapdragon 7s Gen 3 - Android 15", makeProfile(
            "Nothing","Nothing","A059","P534","P534",
            "Nothing/P534/A059:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-03-01",1080,2412,480,"Snapdragon 7s Gen 3","Qualcomm")));
        list.add(new DevicePreset("nothing_phone2", "Nothing", "Phone (2)", "Snapdragon 8+ Gen 1 - Android 14", makeProfile(
            "Nothing","Nothing","A065","P532","P532",
            "Nothing/P532/A065:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",1080,2412,480,"Snapdragon 8+ Gen 1","Qualcomm")));
        list.add(new DevicePreset("nothing_phone2a", "Nothing", "Phone (2a)", "Dimensity 7200 Pro - Android 14", makeProfile(
            "Nothing","Nothing","A142","P533","P533",
            "Nothing/P533/A142:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-07-01",1080,2412,480,"Dimensity 7200 Pro","MediaTek")));
        list.add(new DevicePreset("oneplus_open", "OnePlus", "OnePlus Open", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "OnePlus","OnePlus","PHN110","pearl","OP5984",
            "OnePlus/OP5984/PHN110:14/UP1A.231005.007/xxx:user/release-keys",
            "UP1A.231005.007","14",34,"2024-08-01",2268,2440,480,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("samsung_tab_s10u", "Samsung", "Galaxy Tab S10 Ultra", "Dimensity 9300+ - Android 14", makeProfile(
            "Samsung","Samsung","SM-X926B","gts10u","gts10ux",
            "samsung/gts10ux/gts10ux:14/UP1A.231005.007/X926BXXU1AXB5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-12-01",1848,2960,420,"Dimensity 9300+","MediaTek")));
        list.add(new DevicePreset("samsung_tab_s9u", "Samsung", "Galaxy Tab S9 Ultra", "Snapdragon 8 Gen 2 - Android 14", makeProfile(
            "Samsung","Samsung","SM-X916B","gts9u","gts9ux",
            "samsung/gts9ux/gts9ux:14/UP1A.231005.007/X916BXXU1BWL5:user/release-keys",
            "UP1A.231005.007","14",34,"2024-09-01",1848,2960,420,"Snapdragon 8 Gen 2","Qualcomm")));
        list.add(new DevicePreset("google_pixel_fold", "Google", "Pixel Fold", "Tensor G2 - Android 14", makeProfile(
            "Google","Google","Pixel Fold","felix","felix",
            "google/felix/felix:14/AP1A.240305.019/11122619:user/release-keys",
            "AP1A.240305.019","14",34,"2024-07-01",1840,2208,480,"Tensor G2","Google")));
        list.add(new DevicePreset("xiaomi_pad7pro", "Xiaomi", "Xiaomi Pad 7 Pro", "Snapdragon 8+ Gen 2 - Android 15", makeProfile(
            "Xiaomi","Xiaomi","24031PN0DC","sheng","sheng",
            "Xiaomi/sheng/sheng:15/AP4A.250105.002/xxx:user/release-keys",
            "AP4A.250105.002","15",35,"2025-01-01",1800,2880,480,"Snapdragon 8+ Gen 2","Qualcomm")));
        return list;
    }

    private static DeviceProfile makeProfile(String brand, String manufacturer, String model,
            String device, String product, String fingerprint, String buildId,
            String release, int sdk, String securityPatch,
            int width, int height, int density, String soc, String socMfr) {
        DeviceProfile p = new DeviceProfile();
        p.setBrand(brand);
        p.setManufacturer(manufacturer);
        p.setModel(model);
        p.setDeviceCode(device);
        p.setProductName(product);
        p.setBuildFingerprint(fingerprint);
        p.setBuildId(buildId);
        p.setBuildRelease(release);
        p.setBuildSdk(sdk);
        p.setSecurityPatch(securityPatch);
        p.setScreenWidth(width);
        p.setScreenHeight(height);
        p.setScreenDensity(density);
        p.setSocModel(soc);
        p.setSocManufacturer(socMfr);
        return p;
    }

    private JSONArray extractPresetArray(String rawJson) throws Exception {
        String trimmed = rawJson == null ? "" : rawJson.trim();
        if (trimmed.startsWith("[")) {
            return new JSONArray(trimmed);
        }
        JSONObject payload = new JSONObject(trimmed);
        JSONArray presets = payload.optJSONArray(CACHE_KEY_PRESETS);
        return presets == null ? new JSONArray() : presets;
    }

    private JSONArray fetchRemotePresetArray(String sourceUrl) throws Exception {
        String apiUrl = toGithubContentsApiUrl(sourceUrl);
        JSONArray contents = new JSONArray(readUrl(apiUrl));
        JSONArray normalizedArray = new JSONArray();

        for (int index = 0; index < contents.length(); index++) {
            JSONObject item = contents.getJSONObject(index);
            if (!"file".equalsIgnoreCase(item.optString("type"))) {
                continue;
            }
            String name = item.optString("name");
            if (!name.toLowerCase(Locale.US).endsWith(".json")) {
                continue;
            }

            String downloadUrl = item.optString("download_url");
            if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
                continue;
            }

            JSONObject sourceJson = new JSONObject(readUrl(downloadUrl));
            normalizedArray.put(normalizePresetJson(name, sourceJson));
        }

        return normalizedArray;
    }

    private JSONObject normalizePresetJson(String fileName, JSONObject sourceJson) throws Exception {
        String fileBaseName = fileName.replaceFirst("(?i)\\.json$", "").trim();
        JSONObject profileJson = sourceJson.optJSONObject("profile");
        if (profileJson == null) {
            profileJson = sourceJson;
        }

        String manufacturer = firstNonBlank(
            profileJson.optString("manufacturer"),
            profileJson.optString("brand")
        );
        String model = firstNonBlank(
            profileJson.optString("model"),
            fileBaseName
        );
        String brandLabel = firstNonBlank(sourceJson.optString("brandLabel"), manufacturer);
        String modelLabel = firstNonBlank(sourceJson.optString("modelLabel"), model);

        JSONObject normalized = new JSONObject();
        normalized.put("id", firstNonBlank(sourceJson.optString("id"), slugify(fileBaseName)));
        normalized.put("brandLabel", brandLabel);
        normalized.put("modelLabel", modelLabel);
        normalized.put("summary", firstNonBlank(sourceJson.optString("summary"), buildSummary(profileJson)));
        normalized.put("profile", profileJson);
        return normalized;
    }

    private List<DevicePreset> parsePresetArray(JSONArray array) {
        List<DevicePreset> presets = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null) {
                continue;
            }
            JSONObject profileJson = item.optJSONObject("profile");
            if (profileJson == null) {
                continue;
            }
            DeviceProfile profile = readProfile(profileJson);
            profile.applyFallbacks();
            presets.add(new DevicePreset(
                item.optString("id", slugify(item.optString("modelLabel"))),
                item.optString("brandLabel", firstNonBlank(profile.getManufacturer(), profile.getBrand())),
                item.optString("modelLabel", profile.getModel()),
                item.optString("summary"),
                profile
            ));
        }
        return presets;
    }

    private String toGithubContentsApiUrl(String sourceUrl) throws Exception {
        String trimmed = AppSettingsStore.DEFAULT_PRESET_SOURCE_URL.equals(sourceUrl)
            ? sourceUrl
            : sourceUrl.trim();
        if (trimmed.startsWith("https://api.github.com/repos/")) {
            if (trimmed.endsWith("/contents")) {
                return trimmed;
            }
            if (trimmed.contains("/contents/")) {
                return trimmed;
            }
        }

        String normalized = trimmed
            .replace("git@github.com:", "https://github.com/")
            .replace(".git", "");
        if (!normalized.startsWith("https://github.com/")) {
            throw new IllegalArgumentException("Only GitHub repository URLs are supported.");
        }

        String[] parts = normalized.substring("https://github.com/".length()).split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GitHub repository URL.");
        }

        String owner = parts[0];
        String repo = parts[1];
        StringBuilder builder = new StringBuilder("https://api.github.com/repos/")
            .append(owner)
            .append("/")
            .append(repo)
            .append("/contents");

        if (parts.length > 4 && "tree".equals(parts[2])) {
            String branch = parts[3];
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 4; i < parts.length; i++) {
                if (pathBuilder.length() > 0) {
                    pathBuilder.append("/");
                }
                pathBuilder.append(parts[i]);
            }
            builder.append("/").append(pathBuilder);
            builder.append("?ref=").append(URLEncoder.encode(branch, StandardCharsets.UTF_8.name()));
        }

        return builder.toString();
    }

    private String readUrl(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "SpoofMyDevice");
        connection.setInstanceFollowRedirects(true);
        try (InputStream inputStream = connection.getInputStream()) {
            return readFully(inputStream);
        } finally {
            connection.disconnect();
        }
    }

    private String readFully(InputStream inputStream) throws Exception {
        try (InputStream stream = inputStream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String buildSummary(JSONObject profileJson) {
        String soc = firstNonBlank(
            profileJson.optString("socModel"),
            profileJson.optString("boardPlatform")
        );
        String release = firstNonBlank(profileJson.optString("buildRelease"), "Android");
        if (soc.isEmpty()) {
            return "Android " + release;
        }
        return soc + " - Android " + release;
    }

    private String buildDefaultUserAgent(DeviceProfile profile) {
        return String.format(
            Locale.US,
            "Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            firstNonBlank(profile.getBuildRelease(), String.valueOf(Build.VERSION.SDK_INT)),
            firstNonBlank(profile.getModel(), "Android")
        );
    }

    private boolean isTablet(DisplayMetrics metrics) {
        if (metrics == null || metrics.densityDpi <= 0) {
            return false;
        }
        int smallestWidthDp = Math.round((Math.min(metrics.widthPixels, metrics.heightPixels) * 160f) / metrics.densityDpi);
        return smallestWidthDp >= 600;
    }

    private String joinAbis(String[] abis) {
        if (abis == null || abis.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String abi : abis) {
            if (abi == null || abi.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(abi.trim());
        }
        return builder.toString();
    }

    private String readBuildField(String fieldName) {
        try {
            Object value = Build.class.getField(fieldName).get(null);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String slugify(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return normalized.replace(" ", "_").replace("-", "_");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private DeviceProfile readProfile(JSONObject jsonObject) {
        DeviceProfile profile = new DeviceProfile();
        profile.setBrand(jsonObject.optString("brand"));
        profile.setManufacturer(jsonObject.optString("manufacturer"));
        profile.setModel(jsonObject.optString("model"));
        profile.setProductName(jsonObject.optString("productName"));
        profile.setDeviceCode(jsonObject.optString("deviceCode"));
        profile.setBoard(jsonObject.optString("board"));
        profile.setHardware(jsonObject.optString("hardware"));
        profile.setBoardPlatform(jsonObject.optString("boardPlatform"));
        profile.setBuildFingerprint(jsonObject.optString("buildFingerprint"));
        profile.setBuildId(jsonObject.optString("buildId"));
        profile.setBuildDisplayId(jsonObject.optString("buildDisplayId"));
        profile.setBuildIncremental(jsonObject.optString("buildIncremental"));
        profile.setBuildRelease(jsonObject.optString("buildRelease"));
        profile.setBuildSdk(jsonObject.optInt("buildSdk"));
        profile.setSecurityPatch(jsonObject.optString("securityPatch"));
        profile.setBuildDescription(jsonObject.optString("buildDescription"));
        profile.setBuildFlavor(jsonObject.optString("buildFlavor"));
        profile.setBuildProduct(jsonObject.optString("buildProduct"));
        profile.setBuildCharacteristics(jsonObject.optString("buildCharacteristics"));
        profile.setScreenWidth(jsonObject.optInt("screenWidth"));
        profile.setScreenHeight(jsonObject.optInt("screenHeight"));
        profile.setScreenDensity(jsonObject.optInt("screenDensity"));
        profile.setOperatorAlpha(jsonObject.optString("operatorAlpha"));
        profile.setOperatorNumeric(jsonObject.optString("operatorNumeric"));
        profile.setSimOperatorAlpha(jsonObject.optString("simOperatorAlpha"));
        profile.setSimOperatorNumeric(jsonObject.optString("simOperatorNumeric"));
        profile.setSimCountryIso(jsonObject.optString("simCountryIso"));
        profile.setTimezone(jsonObject.optString("timezone"));
        profile.setUserAgent(jsonObject.optString("userAgent"));
        profile.setSerialNumber(jsonObject.optString("serialNumber"));
        profile.setBootloader(jsonObject.optString("bootloader"));
        profile.setAndroidId(jsonObject.optString("androidId"));
        profile.setCpuAbi(jsonObject.optString("cpuAbi"));
        profile.setCpuAbiList(jsonObject.optString("cpuAbiList"));
        profile.setCpuAbiList64(jsonObject.optString("cpuAbiList64"));
        profile.setCpuAbiList32(jsonObject.optString("cpuAbiList32"));
        profile.setSocModel(jsonObject.optString("socModel"));
        profile.setSocManufacturer(jsonObject.optString("socManufacturer"));
        return profile;
    }
}
