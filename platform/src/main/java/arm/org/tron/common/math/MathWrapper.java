package org.tron.common.math;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class MathWrapper {


  private static final Map<PowData, Double> powData = Collections.synchronizedMap(new HashMap<>());
  private static final String POW_B1 = "3f40624dd2f1a9fc"; // 1/2000 = 0.0005
  private static final String POW_B2 = "409f400000000000"; // 2000

  /**
   * This static block is used to initialize the data map.
   */
  static {
    addPowData("3ff000000669e439", POW_B1, "3ff000000000d22a");
    addPowData("3ff000000b4fe3ac", POW_B1, "3ff00000000172ac");
    addPowData("3ff0000015d11937", POW_B1, "3ff000000002cae4");
    addPowData("3ff000001adebd1f", POW_B1, "3ff000000003707a");
    addPowData("3ff000002fc6a33f", POW_B1, "3ff0000000061d86");
    addPowData("3ff0000046d74585", POW_B1, "3ff0000000091150");
    addPowData("3ff000005454cb56", POW_B1, "3ff00000000acb5e");
    addPowData("3ff000005d4df1d8", POW_B1, "3ff00000000bf166");
    addPowData("3ff0000071929b3b", POW_B1, "3ff00000000e898c");
    addPowData("3ff00000a9d6d98d", POW_B1, "3ff000000015bd4a");
    addPowData("3ff00000c2a51ab7", POW_B1, "3ff000000018ea20");
    addPowData("3ff000012f2af5bf", POW_B1, "3ff000000026ce32");
    addPowData("3ff00001413724bf", POW_B1, "3ff0000000291d94");
    addPowData("3ff0000153bd4e6d", POW_B1, "3ff00000002b7c94");
    addPowData("3ff000015f75ad2a", POW_B1, "3ff00000002cfca0");
    addPowData("3ff0000198da81dd", POW_B1, "3ff000000034554e");
    addPowData("3ff00001b178bb95", POW_B1, "3ff0000000377bfc");
    addPowData("3ff00001ce277ce7", POW_B1, "3ff00000003b27dc");
    addPowData("3ff0000294b2be1a", POW_B1, "3ff00000005491bc");
    addPowData("3ff00002f453d343", POW_B1, "3ff000000060cf4e");
    addPowData("3ff000045a0b2035", POW_B1, "3ff00000008e98e6");
    addPowData("3ff000045d0cfef6", POW_B1, "3ff00000008efb72");
    addPowData("3ff00004e5545390", POW_B1, "3ff0000000a06d00");
    addPowData("3ff000057b83c83f", POW_B1, "3ff0000000b3a640");
    addPowData("3ff00005980b881e", POW_B1, "3ff0000000b74d20");
    addPowData("3ff00005983e8eb0", POW_B1, "3ff0000000b753a8");
    addPowData("3ff00005c7692d61", POW_B1, "3ff0000000bd5d34");
    addPowData("3ff00006666f30ff", POW_B1, "3ff0000000d1b80e");
    addPowData("3ff0000736bc4e32", POW_B1, "3ff0000000ec61a0");
    addPowData("3ff000078694d925", POW_B1, "3ff0000000f699fe");
    addPowData("3ff0000965922b01", POW_B1, "3ff000000133e966");
    addPowData("3ff00009d3df2e9c", POW_B1, "3ff00000014207b4");
    addPowData("3ff0000a7fc1031c", POW_B1, "3ff00000015807e2");
    addPowData("3ff0000b24e284fd", POW_B1, "3ff00000016d2ad6");
    addPowData("3ff0000ba3ef2a27", POW_B1, "3ff00000017d6df2");
    addPowData("3ff0000cc84e613f", POW_B1, "3ff0000001a2da46");
    addPowData("3ff0000d3f510930", POW_B1, "3ff0000001b215f6");
    addPowData("3ff0000ed6a63814", POW_B1, "3ff0000001e63942");
    addPowData("3ff0000faaddda28", POW_B1, "3ff0000002016318");
    addPowData("3ff0000fca014900", POW_B1, "3ff0000002055f6c");
    addPowData("3ff00010e0b096e8", POW_B1, "3ff0000002290b3a");
    addPowData("3ff00011fc8e48d6", POW_B1, "3ff00000024d60ca");
    addPowData("3ff00013f7a36c74", POW_B1, "3ff00000028e4892");
    addPowData("3ff0001463399c1f", POW_B1, "3ff00000029c0de8");
    addPowData("3ff00015993ff1af", POW_B1, "3ff0000002c3bc98");
    addPowData("3ff00015c8f06afe", POW_B1, "3ff0000002c9d73e");
    addPowData("3ff00015ca4ae425", POW_B1, "3ff0000002ca0398");
    addPowData("3ff0001632cccf1b", POW_B1, "3ff0000002d76406");
    addPowData("3ff000163356b88b", POW_B1, "3ff0000002d775ac");
    addPowData("3ff00016f40da515", POW_B1, "3ff0000002f02068");
    addPowData("3ff00019f2cf3156", POW_B1, "3ff00000035244e2");
    addPowData("3ff0001a642c16b8", POW_B1, "3ff000000360c778");
    addPowData("3ff0001b5d3e01b7", POW_B1, "3ff000000380a8c8");
    addPowData("3ff0001c9734868b", POW_B1, "3ff0000003a8d872");
    addPowData("3ff0001d25c4a5a3", POW_B1, "3ff0000003bb17d2");
    addPowData("3ff0002175e6b56b", POW_B1, "3ff0000004486afa");
    addPowData("3ff00021d4f4d524", POW_B1, "3ff00000045495a4");
    addPowData("3ff00021f6080e3c", POW_B1, "3ff000000458d16a");
    addPowData("3ff00022373a14e0", POW_B1, "3ff00000046129aa");
    addPowData("3ff000228ada77a7", POW_B1, "3ff00000046bddda");
    addPowData("3ff0002514302bc2", POW_B1, "3ff0000004befa86");
    addPowData("3ff00028864aa46e", POW_B1, "3ff00000052fe238");
    addPowData("3ff0002cc166be3c", POW_B1, "3ff0000005ba841e");
    addPowData("3ff0002d663f7849", POW_B1, "3ff0000005cf9d94");
    addPowData("3ff00030ecbec180", POW_B1, "3ff0000006432148");
    addPowData("3ff00032bc7bd1fb", POW_B1, "3ff00000067e7c58");
    addPowData("3ff00033d5ab51c8", POW_B1, "3ff0000006a279c8");
    addPowData("3ff000342811fe2a", POW_B1, "3ff0000006ad05c4");
    addPowData("3ff0003757f8a604", POW_B1, "3ff00000071573d6");
    addPowData("3ff0003fd1c236d7", POW_B1, "3ff00000082b2b64");
    addPowData("3ff00040f8b820de", POW_B1, "3ff000000850ec12");
    addPowData("3ff00042afe6956a", POW_B1, "3ff0000008892244");
    addPowData("3ff000472fce0067", POW_B1, "3ff00000091c9160");
    addPowData("3ff000489c0f28bd", POW_B1, "3ff00000094b3072");
    addPowData("3ff00051c09cc796", POW_B1, "3ff000000a76c20e");
    addPowData("3ff00056dbfd9971", POW_B1, "3ff000000b1e16c8");
    addPowData("3ff00057dfaf3d77", POW_B1, "3ff000000b3f53b8");
    addPowData("3ff000591fba3154", POW_B1, "3ff000000b684a00");
    addPowData("3ff0005b7357c2d4", POW_B1, "3ff000000bb48572");
    addPowData("3ff0005db6c24365", POW_B1, "3ff000000bfeae14");
    addPowData("3ff00066e3aaf20c", POW_B1, "3ff000000d2b4fea");
    addPowData("3ff00067740a0dfe", POW_B1, "3ff000000d3dca36");
    addPowData("3ff00068def18101", POW_B1, "3ff000000d6c3cac");
    addPowData("3ff0006e631be9f7", POW_B1, "3ff000000e20f9c6");
    addPowData("3ff00077944ddc87", POW_B1, "3ff000000f4e26d2");
    addPowData("3ff0007e094cbb50", POW_B1, "3ff000001021b5d6");
    addPowData("3ff0007f93d4171e", POW_B1, "3ff0000010543432");
    addPowData("3ff0008b8fa77733", POW_B1, "3ff0000011dcd5e8");
    addPowData("3ff0008c9af73595", POW_B1, "3ff0000011ff0c00");
    addPowData("3ff000918683e6b9", POW_B1, "3ff0000012a03f4c");
    addPowData("3ff0009ea781c5dc", POW_B1, "3ff00000144e644e");
    addPowData("3ff000a1bfba1cad", POW_B1, "3ff0000014b3c7d0");
    addPowData("3ff000a2fd62cd32", POW_B1, "3ff0000014dc6f40");
    addPowData("3ff000a87b8e3d21", POW_B1, "3ff0000015906556");
    addPowData("3ff000a98a72819b", POW_B1, "3ff0000015b3107c");
    addPowData("3ff000ab7bfa76bc", POW_B1, "3ff0000015f2bcf0");
    addPowData("3ff000ab8a035de5", POW_B1, "3ff0000015f488c0");
    addPowData("3ff000b83b92666f", POW_B1, "3ff000001794683e");
    addPowData("3ff000bb1c2e1917", POW_B1, "3ff0000017f2ad26");
    addPowData("3ff000c0e8df0274", POW_B1, "3ff0000018b0aeb2");
    addPowData("3ff000c3dcd52810", POW_B1, "3ff0000019116d74");
    addPowData("3ff000c66ab34f1a", POW_B1, "3ff0000019651b5e");
    addPowData("3ff000c6c0333d8d", POW_B1, "3ff0000019700c7e");
    addPowData("3ff000da5dac4a20", POW_B1, "3ff000001bf2ab68");
    addPowData("3ff000dad9042c59", POW_B1, "3ff000001c027448");
    addPowData("3ff000def05fa9c8", POW_B1, "3ff000001c887cdc");
    addPowData("3ff000e7d0d1886a", POW_B1, "3ff000001dab4c32");
    addPowData("3ff000ee89e5c6e1", POW_B1, "3ff000001e878be0");
    addPowData("3ff000efedd98c6e", POW_B1, "3ff000001eb51910");
    addPowData("3ff000f2785d7ac7", POW_B1, "3ff000001f085840");
    addPowData("3ff0010403f34767", POW_B1, "3ff0000021472146");
    addPowData("3ff0010b331a0d17", POW_B1, "3ff0000022327b72");
    addPowData("3ff0010cd9aeb281", POW_B1, "3ff0000022688f08");
    addPowData("3ff00121ef66cfaf", POW_B1, "3ff00000251b4868");
    addPowData("3ff0012e43815868", POW_B1, "3ff0000026af266e");
    addPowData("3ff0012eaddc6e6c", POW_B1, "3ff0000026bcc27e");
    addPowData("3ff001342f8076a1", POW_B1, "3ff0000027712428");
    addPowData("3ff0013bca543227", POW_B1, "3ff00000286a42d2");
    addPowData("3ff0013c2556de0d", POW_B1, "3ff000002875e826");
    addPowData("3ff00142945996d8", POW_B1, "3ff000002948a8f8");
    addPowData("3ff0014b030edd2b", POW_B1, "3ff000002a5ce37e");
    addPowData("3ff00152d0067fdc", POW_B1, "3ff000002b5c6b3c");
    addPowData("3ff00157b53c4b1b", POW_B1, "3ff000002bfcc712");
    addPowData("3ff0015c35ba4a3e", POW_B1, "3ff000002c903f7c");
    addPowData("3ff0016a01a8e426", POW_B1, "3ff000002e542eac");
    addPowData("3ff0016e89d3a3d4", POW_B1, "3ff000002ee8a1d4");
    addPowData("3ff0019142307bb4", POW_B1, "3ff000003359ed28");
    addPowData("3ff0019b46fd6dd3", POW_B1, "3ff0000034a21802");
    addPowData("3ff001a09de2304b", POW_B1, "3ff000003550fcb6");
    addPowData("3ff001aa5e824b31", POW_B1, "3ff0000036906d46");
    addPowData("3ff001d58a281371", POW_B1, "3ff000003c166f1a");
    addPowData("3ff001eb2c463f76", POW_B1, "3ff000003edafd18");
    addPowData("3ff001ed83fcb397", POW_B1, "3ff000003f27b742");
    addPowData("3ff00208430b92aa", POW_B1, "3ff000004293b74e");
    addPowData("3ff0020e7a8cf479", POW_B1, "3ff00000435f53c0");
    addPowData("3ff0021a2f14a0ee", POW_B1, "3ff0000044deb040");
    addPowData("3ff00225d2bdab65", POW_B1, "3ff00000465be328");
    addPowData("3ff0023b7ef88d11", POW_B1, "3ff000004921ae72");
    addPowData("3ff0025118554352", POW_B1, "3ff000004be50c14");
    addPowData("3ff0026155666d19", POW_B1, "3ff000004df8d8d4");
    addPowData("3ff002741437f128", POW_B1, "3ff00000505ebbd4");
    addPowData("3ff00277c54a46dc", POW_B1, "3ff0000050d7a15a");
    addPowData("3ff0027e7383d6a3", POW_B1, "3ff0000051b26818");
    addPowData("3ff002c4fc4fdc0a", POW_B1, "3ff000005ab8317e");
    addPowData("3ff002c85c832a94", POW_B1, "3ff000005b26bc70");
    addPowData("3ff002cb8db1cdd2", POW_B1, "3ff000005b8f43a8");
    addPowData("3ff002ff8dc81d17", POW_B1, "3ff0000062360202");
    addPowData("3ff00307dd1df5e8", POW_B1, "3ff0000063461b4c");
    addPowData("3ff00314b1e73ecf", POW_B1, "3ff0000064ea3ef8");
    addPowData("3ff0032fda05447d", POW_B1, "3ff0000068636fe0");
    addPowData("3ff003443fe32ca3", POW_B1, "3ff000006aff4f62");
    addPowData("3ff00358e0494db1", POW_B1, "3ff000006da2a7fc");
    addPowData("3ff0035d6b21692b", POW_B1, "3ff000006e3760e2");
    addPowData("3ff00364ba163146", POW_B1, "3ff000006f26a9dc");
    addPowData("3ff00370ee36a27f", POW_B1, "3ff0000070b637f4");
    addPowData("3ff0039d1f6e2a69", POW_B1, "3ff00000765d10a0");
    addPowData("3ff003f5ccdc2a0e", POW_B1, "3ff0000081b42a4c");
    addPowData("3ff0040f75988e6a", POW_B1, "3ff0000084fc2434");
    addPowData("3ff0041cf1d045a6", POW_B1, "3ff0000086b595c6");
    addPowData("3ff0041d9e7db622", POW_B1, "3ff0000086cbaa66");
    addPowData("3ff0043174f1cecf", POW_B1, "3ff0000089550ca2");
    addPowData("3ff00435791bd0f7", POW_B1, "3ff0000089d88502");
    addPowData("3ff00466bb29aef9", POW_B1, "3ff000009024e9a4");
    addPowData("3ff004824e602aa4", POW_B1, "3ff0000093ab82ba");
    addPowData("3ff0048e35a7cb3d", POW_B1, "3ff00000953121ae");
    addPowData("3ff0048fbb17c5da", POW_B1, "3ff000009562ec96");
    addPowData("3ff004901a2243db", POW_B1, "3ff00000956f136c");
    addPowData("3ff00496fe59bc98", POW_B1, "3ff000009650a4ca");
    addPowData("3ff004a6d1ff4ea8", POW_B1, "3ff000009856aba0");
    addPowData("3ff004b668c99125", POW_B1, "3ff000009a54e898");
    addPowData("3ff005033e4be951", POW_B1, "3ff00000a4279f34");
    addPowData("3ff005468a327822", POW_B1, "3ff00000acc20750");
    addPowData("3ff005583aa2b489", POW_B1, "3ff00000af04eb28");
    addPowData("3ff0058e587f1f45", POW_B1, "3ff00000b5efdb84");
    addPowData("3ff00594e6478777", POW_B1, "3ff00000b6c6527e");
    addPowData("3ff005c517af10c9", POW_B1, "3ff00000bcef536c");
    addPowData("3ff005fd34c3ed15", POW_B1, "3ff00000c41b6c0c");
    addPowData("3ff00605a05f9aa0", POW_B1, "3ff00000c52eefa6");
    addPowData("3ff00659bb10a908", POW_B1, "3ff00000cfeeb5f0");
    addPowData("3ff0068cd52978ae", POW_B1, "3ff00000d676966c");
    addPowData("3ff006e4da5039f7", POW_B1, "3ff00000e1b61b90");
    addPowData("3ff006e82e891fef", POW_B1, "3ff00000e223023e");
    addPowData("3ff006ea73f88946", POW_B1, "3ff00000e26d4ea2");
    addPowData("3ff006f9bbd18d8d", POW_B1, "3ff00000e4612d2c");
    addPowData("3ff0071031085c9b", POW_B1, "3ff00000e73fd148");
    addPowData("3ff007183010ac0b", POW_B1, "3ff00000e84562c6");
    addPowData("3ff0076b514de586", POW_B1, "3ff00000f2e491be");
    addPowData("3ff007a23c6d5b72", POW_B1, "3ff00000f9e8d688");
    addPowData("3ff007ba0d6b9092", POW_B1, "3ff00000fcf3cb14");
    addPowData("3ff00818a10a8fa7", POW_B1, "3ff000010908e8cc");
    addPowData("3ff0081d9a5961f3", POW_B1, "3ff0000109ab922e");
    addPowData("3ff00842204a3715", POW_B1, "3ff000010e55fa30");
    addPowData("3ff0088ee1bcbc5c", POW_B1, "3ff000011823f4e8");
    addPowData("3ff008feca56dfb2", POW_B1, "3ff00001266f2416");
    addPowData("3ff009153b12f7fb", POW_B1, "3ff00001294cd942");
    addPowData("3ff0091653a67e2a", POW_B1, "3ff000012970aed8");
    addPowData("3ff0091e30077029", POW_B1, "3ff000012a71b252");
    addPowData("3ff0091e8cbf5d10", POW_B1, "3ff000012a7d89c6");
    addPowData("3ff00939e8deaf04", POW_B1, "3ff000012dfc1048");
    addPowData("3ff0094792ff27e8", POW_B1, "3ff000012fbad0ac");
    addPowData("3ff009645e4bb389", POW_B1, "3ff0000133683264");
    addPowData("3ff009b0b2616930", POW_B1, "3ff000013d27849e");
    addPowData("3ff009b9e70187a2", POW_B1, "3ff000013e547404");
    addPowData("3ff009de0cb6ef67", POW_B1, "3ff0000142f21a62");
    addPowData("3ff009ea31bdbe75", POW_B1, "3ff00001447f19aa");
    addPowData("3ff009f707e9a97b", POW_B1, "3ff000014622b662");
    addPowData("3ff009fb36d3c998", POW_B1, "3ff0000146ab74ec");
    addPowData("3ff00a28820b8780", POW_B1, "3ff000014c7401ae");
    addPowData("3ff00a3632db72be", POW_B1, "3ff000014e3382a6");
    addPowData("3ff00a37999dc7cf", POW_B1, "3ff000014e61513c");
    addPowData("3ff00aa7c8696175", POW_B1, "3ff000015cb3fc44");
    addPowData("3ff00abd92d5068e", POW_B1, "3ff000015f7c2a02");
    addPowData("3ff00aed7ecfd407", POW_B1, "3ff00001659a5334");
    addPowData("3ff00b1fa2b4a6a9", POW_B1, "3ff000016c00e92e");
    addPowData("3ff00b2d0eb64e9f", POW_B1, "3ff000016db786be");
    addPowData("3ff00b69e36600ef", POW_B1, "3ff00001757b59f8");
    addPowData("3ff00bef8115b65d", POW_B1, "3ff0000186893de0");
    addPowData("3ff00bf4c7765387", POW_B1, "3ff000018735966e");
    addPowData("3ff00bfe8ddc73ff", POW_B1, "3ff000018874f61e");
    addPowData("3ff00c15de2b0d5e", POW_B1, "3ff000018b6eaab6");
    addPowData("3ff00c7dd4479905", POW_B1, "3ff0000198b316ac");
    addPowData("3ff00d276f3b5ce5", POW_B1, "3ff00001ae576102");
    addPowData("3ff00d27ce901c9a", POW_B1, "3ff00001ae638ad2");
    addPowData("3ff00d67c2b33446", POW_B1, "3ff00001b68c6810");
    addPowData("3ff00dafadce5c27", POW_B1, "3ff00001bfb9439e");
    addPowData("3ff00dcf80fe76bb", POW_B1, "3ff00001c3c89e06");
    addPowData("3ff00e00380e10d7", POW_B1, "3ff00001c9ff83c8");
    addPowData("3ff00e5ccd725b39", POW_B1, "3ff00001d5ced49a");
    addPowData("3ff00e86a21944c7", POW_B1, "3ff00001db24b940");
    addPowData("3ff00e86a7859088", POW_B1, "3ff00001db256a52");
    addPowData("3ff00eb271b30fab", POW_B1, "3ff00001e0bb385a");
    addPowData("3ff00f01ca36b0b7", POW_B1, "3ff00001ead9d548");
    addPowData("3ff00f3fdf937c0f", POW_B1, "3ff00001f2c4b382");
    addPowData("3ff00f83f7bdfa67", POW_B1, "3ff00001fb73ac9e");
    addPowData("3ff0103649af599a", POW_B1, "3ff00002123055bc");
    addPowData("3ff010e5e83c7501", POW_B1, "3ff000022893e022");
    addPowData("3ff01146bafecacc", POW_B1, "3ff0000234eb6822");
    addPowData("3ff011b575f20dec", POW_B1, "3ff00002430864f4");
    addPowData("3ff011c0fef22410", POW_B1, "3ff000024480bffe");
    addPowData("3ff011cd94e46714", POW_B1, "3ff00002461b60b0");
    addPowData("3ff0123e52985644", POW_B1, "3ff0000254797fd0");
    addPowData("3ff0124f4152a403", POW_B1, "3ff0000256a1e184");
    addPowData("3ff0126d052860e2", POW_B1, "3ff000025a6cde26");
    addPowData("3ff01270a65c85d5", POW_B1, "3ff000025ae345f4");
    addPowData("3ff012c4fd4385cd", POW_B1, "3ff0000265a26b38");
    addPowData("3ff012d826f868c8", POW_B1, "3ff0000268137b10");
    addPowData("3ff0131d07497794", POW_B1, "3ff0000270da035c");
    addPowData("3ff01340687303d7", POW_B1, "3ff00002755bedb6");
    addPowData("3ff01349f3ac164b", POW_B1, "3ff000027693328a");
    addPowData("3ff0142094f13c33", POW_B1, "3ff0000291ea8804");
    addPowData("3ff014797ba4c0ef", POW_B1, "3ff000029d3d4906");
    addPowData("3ff014b591802818", POW_B1, "3ff00002a4e451b6");
    addPowData("3ff014cf1b9413aa", POW_B1, "3ff00002a824f9aa");
    addPowData("3ff014da477e1774", POW_B1, "3ff00002a9913162");
    addPowData("3ff0150325b205a6", POW_B1, "3ff00002aec58ca0");
    addPowData("3ff01545322f876f", POW_B1, "3ff00002b72eba66");
    addPowData("3ff015cba20ec276", POW_B1, "3ff00002c84cef0e");
    addPowData("3ff016571b207ee8", POW_B1, "3ff00002da0eb434");
    addPowData("3ff016d3dfdc8cc0", POW_B1, "3ff00002e9f0b5ea");
    addPowData("3ff01743c6cc53ca", POW_B1, "3ff00002f82f0348");
    addPowData("3ff0177da6425b0e", POW_B1, "3ff00002ff8ca296");
    addPowData("3ff01789d91cbe83", POW_B1, "3ff00003011a146c");
    addPowData("3ff017ac17ac002d", POW_B1, "3ff000030575c5da");
    addPowData("3ff0189be8b70297", POW_B1, "3ff0000323fa0fe8");
    addPowData("3ff018abdf39553b", POW_B1, "3ff0000326020250");
    addPowData("3ff0192278704be3", POW_B1, "3ff000033518c576");
    addPowData("3ff019be4095d6ae", POW_B1, "3ff0000348e9f02a");
    addPowData("3ff019ce7bda4503", POW_B1, "3ff000034afa7cee");
    addPowData("3ff01a4586c04fe7", POW_B1, "3ff000035a1ea55a");
    addPowData("3ff01b15d687a1cf", POW_B1, "3ff00003749c73ac");
    addPowData("3ff01bec8f058641", POW_B1, "3ff000038fe982c2");
    addPowData("3ff01c1791d40d0a", POW_B1, "3ff00003956153b8");
    addPowData("3ff01c1d9e395eba", POW_B1, "3ff0000396262bf8");
    addPowData("3ff01d11a2a555de", POW_B1, "3ff00003b52aba6c");
    addPowData("3ff01d1c6f0356e4", POW_B1, "3ff00003b68a12bc");
    addPowData("3ff01d2883db0d7b", POW_B1, "3ff00003b8132994");
    addPowData("3ff01e71e773ffc2", POW_B1, "3ff00003e1eea894");
    addPowData("3ff01f434d3c5e64", POW_B1, "3ff00003fc88ecc0");
    addPowData("3ff01f6112a7fe03", POW_B1, "3ff0000400511134");
    addPowData("3ff020fb74e9f170", POW_B1, "3ff00004346fbfa2");
    addPowData("3ff021a0782fbc23", POW_B1, "3ff0000449634c62");
    addPowData("3ff0232e074df506", POW_B1, "3ff000047bda0f6c");
    addPowData("3ff02414e9f5c03b", POW_B1, "3ff0000499267fd0");
    addPowData("3ff02430709f51ec", POW_B1, "3ff000049ca49698");
    addPowData("3ff024c8eb334fe8", POW_B1, "3ff00004affcebd4");
    addPowData("3ff02505e61f7b8d", POW_B1, "3ff00004b7b94920");
    addPowData("3ff025cadab76a1f", POW_B1, "3ff00004d0b4be2e");
    addPowData("3ff025e4a878d29f", POW_B1, "3ff00004d3fa8b08");
    addPowData("3ff0262e9bbe0441", POW_B1, "3ff00004dd5b75f0");
    addPowData("3ff0270f120aff91", POW_B1, "3ff00004f9d1f76e");
    addPowData("3ff02754e840e5b2", POW_B1, "3ff0000502acb29a");
    addPowData("3ff02c36701515db", POW_B1, "3ff00005a1002d44");
    addPowData("3ff032d08c7e2e21", POW_B1, "3ff0000676db002c");
    addPowData("3ff035a55ff1c78a", POW_B1, "3ff00006d27732bc");
    addPowData("3ff03644918d0785", POW_B1, "3ff00006e693debe");
    addPowData("3ff037bb1dedc8cd", POW_B1, "3ff0000715e28914");
    addPowData("3ff0395604cd3567", POW_B1, "3ff0000749c3c032");
    addPowData("3ff039bf04e42f96", POW_B1, "3ff000075704c2c6");
    addPowData("3ff03e7bdeeecc32", POW_B1, "3ff00007f004ed7a");
    addPowData("3ff03ed24e556ded", POW_B1, "3ff00007faea95ee");
    addPowData("3ff03ef1c681f0bd", POW_B1, "3ff00007fee23120");
    addPowData("3ff03fcb5661fa8f", POW_B1, "3ff000081a4eb43e");
    addPowData("3ff042d910585bee", POW_B1, "3ff000087ccc6c26");
    addPowData("3ff0450fda5471a3", POW_B1, "3ff00008c42a03da");
    addPowData("3ff04641fc8b11ca", POW_B1, "3ff00008eab1bc98");
    addPowData("3ff047a41ad06417", POW_B1, "3ff0000917400204");
    addPowData("3ff04db90c5c127d", POW_B1, "3ff00009daf8d8ee");
    addPowData("3ff04f9e73a25ac8", POW_B1, "3ff0000a17eef48e");
    addPowData("3ff04fcbf8abc9fd", POW_B1, "3ff0000a1da61602");
    addPowData("3ff0511862bf450d", POW_B1, "3ff0000a4760eee0");
    addPowData("3ff051210e0bfeb6", POW_B1, "3ff0000a48777d74");
    addPowData("3ff055982b0f8b04", POW_B1, "3ff0000ad7de456c");
    addPowData("3ff05727a94317f9", POW_B1, "3ff0000b09f2a4ac");
    addPowData("3ff05e9f2de8eae8", POW_B1, "3ff0000bf9504088");
    addPowData("3ff066a0bcc57b6b", POW_B1, "3ff0000cf97de4b2");
    addPowData("3ff068689beee8b2", POW_B1, "3ff0000d3267db9a");
    addPowData("3ff06c311b09de1e", POW_B1, "3ff0000dab3d2c1e");
    addPowData("3ff06fbdc15a990b", POW_B1, "3ff0000e1c81b8d0");
    addPowData("3ff07003413b4e40", POW_B1, "3ff0000e252a9014");
    addPowData("3ff0707b97ed6426", POW_B1, "3ff0000e3428b132");
    addPowData("3ff077df0910b501", POW_B1, "3ff0000f1f96de9e");
    addPowData("3ff07f468476cd37", POW_B1, "3ff000100b1bf6fa");
    addPowData("3ff08da4fce37b7d", POW_B1, "3ff00011d2fefcd4");
    addPowData("3ff08ee23b88ee24", POW_B1, "3ff00011fa3db176");
    addPowData("3ff0907c57df2523", POW_B1, "3ff000122cf4fcd0");
    addPowData("3ff09d1632ec4b9a", POW_B1, "3ff00013bb369796");
    addPowData("3ff09e92f87b35af", POW_B1, "3ff00013ea2501a4");
    addPowData("3ff0a900bef42c6d", POW_B1, "3ff0001532be2c26");
    addPowData("3ff0b33bfdb9a6c8", POW_B1, "3ff000167457a5ce");
    addPowData("3ff0b385e0945b49", POW_B1, "3ff000167d6737f2");
    addPowData("3ff0c1dd413c5db1", POW_B1, "3ff000183edd3e2a");
    addPowData("3ff0c68383edcd8b", POW_B1, "3ff00018d041d5b4");
    addPowData("3ff0d0d3019dddb8", POW_B1, "3ff0001a121dc50e");
    addPowData("3ff0dd223ceffaf9", POW_B1, "3ff0001b915e94ac");
    addPowData("3ff0ef73f1a9aa75", POW_B1, "3ff0001dc9b6eb1a");
    addPowData("3ff15e3c3258a73d", POW_B1, "3ff0002b045ced4c");
    addPowData("3ff29c4ea7efe276", POW_B1, "3ff0004f3e9a3c94");
    addPowData("3ff2e90398bec6e7", POW_B1, "3ff000579e8208fa");
    addPowData("3ff318ed7b598d20", POW_B1, "3ff0005cc8807672");
    addPowData("3ff348ec880ef24d", POW_B1, "3ff00061e7db55c4");
    addPowData("3ff37391b11ff076", POW_B1, "3ff000666a5c8088");
    addPowData("3ff515fde2e99b0d", POW_B1, "3ff00090b8cf57de");
    addPowData("3ff6a89b5ffae2dd", POW_B1, "3ff000b67158530a");
    addPowData("3ff99efec0fbc5d8", POW_B1, "3ff000f6e0b478fe");
    addPowData("3ffffcf9acb020be", POW_B1, "3ff0016b472e0602");
    addPowData("3ff000df8a0eadf8", POW_B2, "3ff8817d68bc901b");
    addPowData("3ff00004ec241f48", POW_B2, "3ff026a33089cf0d");
    addPowData("3ff0152243bac553", POW_B1, "3ff00002b2bbfc38"); // for x86 jdk17 on 4143944
  }

  private static void addPowData(String a, String b, String ret) {
    powData.put(new PowData(hexToDouble(a), hexToDouble(b)), hexToDouble(ret));
  }


  // for arm jdk17
  public static double pow(double a, double b) {
    double strictResult = StrictMath.pow(a, b);
    return powData.getOrDefault(new PowData(a, b), strictResult);
  }

  private static double hexToDouble(String input) {
    // Convert the hex string to a long
    long hexAsLong = Long.parseLong(input, 16);
    // and then convert the long to a double
    return Double.longBitsToDouble(hexAsLong);
  }

  private static class PowData {
    final double a;
    final double b;

    public PowData(double a, double b) {
      this.a = a;
      this.b = b;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PowData powData = (PowData) o;
      return Double.compare(powData.a, a) == 0 && Double.compare(powData.b, b) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(a, b);
    }
  }

  public static long addExact(long x, long y) {
    return StrictMath.addExact(x, y);
  }

  public static int addExact(int x, int y) {
    return StrictMath.addExact(x, y);
  }

  public static long floorDiv(long x, long y) {
    return StrictMath.floorDiv(x, y);
  }

  public static int multiplyExact(int x, int y) {
    return StrictMath.multiplyExact(x, y);
  }

  public static long multiplyExact(long x, long y) {
    return StrictMath.multiplyExact(x, y);
  }

  public static long subtractExact(long x, long y) {
    return StrictMath.subtractExact(x, y);
  }

  public static int min(int a, int b) {
    return StrictMath.min(a, b);
  }

  public static long min(long a, long b) {
    return StrictMath.min(a, b);
  }

  public static int max(int a, int b) {
    return StrictMath.max(a, b);
  }

  public static long max(long a, long b) {
    return StrictMath.max(a, b);
  }

  public static int round(float a) {
    return StrictMath.round(a);
  }

  public static long round(double a) {
    return StrictMath.round(a);
  }

  public static double ceil(double a) {
    return StrictMath.ceil(a);
  }

  public static double signum(double a) {
    return StrictMath.signum(a);
  }

  public static double random() {
    return StrictMath.random();
  }

  public static long abs(long a) {
    return StrictMath.abs(a);
  }
}
