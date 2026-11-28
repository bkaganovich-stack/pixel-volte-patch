# Pixel 10 Pro XL (mustang, CP4A.261105.040.A1): cfg.db findings

Factory image SHA-256 matches (92ffc289…). cfg.db from vendor.img:/firmware/carrierconfig
(confpack cfgdb-zmbu_p25-261007-B-16536938, sha256 eac7a8d6…).

## Profile selection for Russia
- carrier_info has 25 rows with MCC 250 (MTS 1678, MegaFon 1016, Tele2/T2 1028, Beeline 1681, ...).
- None has a confmap row -> regional_fallback (0) -> `wildcard` profile.
- confmap has 449 rows, 281 confman_* tables, 1064 confseqs, 281 manifests.

## The difference is 2 small confseqs
- wildcard_5g (20009) = wildcard + `wildcard-5g.sim1/.sim2`
- wildcard_5gsa (20033) = wildcard + wildcard-5g.* + `wildcard-5gsa.sim1/.sim2`
- confseq = protobuf: {1:"v1.0", 2:name, 4*: {1:key_hash, 2:{3:int}}}; some blobs are CLZ4-compressed.
- wildcard-5g.sim1: 0x27d28f4e=1, 0x6ef0974d=1, 0x5d4f1ee5=10, 0x46ac52b8=1, 0x891cc52e=1
- wildcard-5gsa.sim1: 0x27d28f4e=17, 0x6ef0974d=17, 0x5d4f1ee5=(empty), 0xc0b0140e=1, 0x1af1bf8b=1, 0xca9148b8=2138
- default.sim1 (base) leaves 0x27d28f4e/0x6ef0974d empty -> NR off. 1 is NSA; 17 (0x11) is NSA+SA
  (telus, kddi-sa and fr_orange use 17).

## Who selects it (vendor libsitril.so, inside rild)
- The modem sends a CarrierConfigSimInfoInd (mccmnc/imsi/spn/gid1/gid2/iccid).
- MiscService::DoOemSetCpCarrierConfig builds a CarrierManifest from it and runs the SQL against cfg.db.
- It then calls ProtocolMiscBuilder::BuildSetCpCarrierConfig(getEncodedManifest()) and sends that to the modem.
- Encoded manifest == manifests/<hash> blob (wildcard_5g: 59ba0914…, 701 bytes).
- Override paths and why they are closed to shell:
  - props persist.vendor.radio.config.{carrier_id,locked_carrier_id,carrier_config_dir}, persist.vendor.radio.nr_avail:
    type vendor_imssvc_prop; only rild, cbd, vendor_init and vendor_ims_app may set them. Closed to shell.
  - IRilExt ("carrier ID now is set by RilExt"): hal_ril_ext_service is findable only by rild and
    rilextensionservice_app. Closed to shell.
  - telephony.oem.oemrilhook (radio_service, gate is MODIFY_PHONE_STATE) is reachable by shell.
    Request id 5 = RILC_REQ_MISC_SET_CARRIER_CONFIG; no Java caller found. The rild-side handler and the payload are UNKNOWN.

## Android side
- OemRilService NetworkModeSelector reads carrier_nr_availabilities_int_array (CarrierConfig) only to set
  allowed network types. That is why the Android override alone is not enough: the modem profile has NR off.

## Next (read-only)
1. Find the rild handler for OEM external request id 5 (libsitril / libril_sitril / rild_exynos).
   Confirm whether the payload is an encoded manifest and whether it persists or is re-sent on SIM events.
2. Only then consider a single call on the SIM-less phone, with a recovery plan.

## Command 5 traced through rild (read-only, static analysis)
- Java: OemRil.invokeRequestRaw(RILC id, data) -> IOemSlsiRadioExternal.sendRequestRaw.
- libril_sitril external_dispatchRaw -> convertRilcReqId2RilOemReqId (jump table at 0x9ad70).
- libsitril RilApplication::externalOnRequest -> ServiceMgr::RouteOemRequest -> s_RilOemRequestServiceMap.
  The map is built by the static ctor at 0x1288b0; 272 entries were rebuilt by emulation.
- Check: RILC 8 GET_MSL_CODE -> OEM 10140 -> Misc 5042 -> vtable 0x198 DoOemGetMslCode (matches).
- RILC 5 SET_CARRIER_CONFIG -> OEM 10137 -> NO ROUTE (rild rejects it). Dead end.
- DoOemSetCpCarrierConfig = Misc request 5215 = vtable slot 0x4b0, reached only from OEM id 11600.
  No RILC id converts to 11600. It takes the modem's SIM-info indication (mccmnc/imsi/spn/gid/iccid),
  not a profile name, then runs its own cfg.db lookup.
- 11600 would be reachable through RIL_REQUEST_OEM_HOOK_RAW (RIL map 59 -> (0,0) = OEM route, payload [u32 id][u32 len][data]).
  But no android.hardware.radio.deprecated IOemHook is registered on the device, and the AIDL radio HAL has no OEM hook.
- Shell cannot find hal_vendor_radio_external_service directly (only radio, oemrilservice_app, ims, gril and others).
- RILC 804 RADIO_CONFIG_RESET -> 11244 -> Misc 5143. Do not use.

## Conclusion
On CP4A.261105.040.A1 there is no rootless path to make the modem load wildcard_5g:
- props: SELinux; IRilExt: SELinux; oemrilhook id 5: unrouted;
- the CP carrier config handler is reachable only through the absent OEM_HOOK_RAW HAL.
Not explored: RILC 54/55 SET_RADIO_NODE_VALUE/EXEC_RADIO_NODE (Misc 5194/5196) and ENG_MODE.
These are NV/eng interfaces, high risk.

## Radio node (RILC 53/54/55) and ENG_MODE (read-only, static analysis)
- Routes confirmed in s_RilOemRequestServiceMap: 10600/10601/10602 -> Misc 5192/5194/5196
  (DoGet/DoSet/DoExecRadioNode). RILC 2 -> 10129 -> Misc 5028 (SetEngModeHandler). All reachable from oemrilhook.
- DoSetRadioNodeValue payload: [u32 node][u32 len][len bytes string]; rild does no validation,
  only ProtocolMiscBuilder::BuildSetRadioNodeValue(node, str, len) -> SIT_SET_RADIO_NODE (reply 5195).
- Modem firmware (g5400i-260818-261007-B) strings: "Convert Radio Node to OMADM Item (%d -> %d)",
  "No matched Radio Node(%d)". Radio nodes are the legacy Sprint OMA-DM item table:
  OMADM_LTE_BAND_*, OMADM_LTE_CA_*, OMADM_LTE_SCAN_PRIORITY_*, OMADM_BAND_25/41_MIMO, OMADM_DL_256_QAM,
  OMADM_UL_64_QAM, OMADM_TM9_ENABLE, OMADM_VOICE_DOMAIN_PREF, OMADM_RTN_*, vocoder/SID/NID items.
  NO NR/5G/ENDC/SA item. Some items write NV (LTE band enable, "LTE is disabled via OMADM").
- ENG_MODE: ProtocolMiscDebugBuilder::SetEngMode(u8 mode, u8 sub) -> SIT_SET_ENG_MODE.
  Toggles the engineering display (UNSOL_DISPLAY_ENG_MODE), not the carrier profile.

## Final conclusion
Neither radio node nor ENG_MODE can switch the modem to wildcard_5g or set NR availability.
Radio node SET is a live, unvalidated write into OMA-DM/NV items (can disable LTE bands) -> do not use.
No rootless path to 5G on CP4A.261105.040.A1 / modem 261007-B.
