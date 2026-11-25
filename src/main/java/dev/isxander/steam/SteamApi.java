package dev.isxander.steam;

public interface SteamApi {
    int k_ESteamAPIInitResult_OK = 0;
    /// Some other failure
    int k_ESteamAPIInitResult_FailedGeneric = 1;
    /// We cannot connect to Steam, steam probably isn't running
    int k_ESteamAPIInitResult_NoSteamClient = 2;
    /// Steam client appears to be out of date
    int k_ESteamAPIInitResult_VersionMismatch = 3;

    int SteamAPI_InitFlat(String[] pOutErrMsg);

    boolean SteamAPI_IsSteamRunning();
}
