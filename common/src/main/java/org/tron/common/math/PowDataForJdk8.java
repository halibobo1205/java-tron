package org.tron.common.math;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

/**
 * This class is used to provide the same pow data for jdk8.
 * only used in ExchangeProcessor.java

 */
class PowDataForJdk8 {

  @Getter
  private  static final Map<Double, Double> data = Collections.synchronizedMap(new HashMap<>());

  /**
   * This static block is used to initialize the data map.
   * The data map is used to store the pow data for jdk8.
   * bach-1 11773524 add 2024-06-11
   */
  static {
    addData("3ff000000669e439", "3ff000000000d22a");
    addData("3ff000000b4fe3ac", "3ff00000000172ac");
    addData("3ff0000015d11937", "3ff000000002cae4");
    addData("3ff000001adebd1f", "3ff000000003707a");
    addData("3ff000002fc6a33f", "3ff0000000061d86");
    addData("3ff0000046d74585", "3ff0000000091150");
    addData("3ff000005454cb56", "3ff00000000acb5e");
    addData("3ff000005d4df1d8", "3ff00000000bf166");
    addData("3ff0000071929b3b", "3ff00000000e898c");
    addData("3ff00000a9d6d98d", "3ff000000015bd4a");
    addData("3ff00000c2a51ab7", "3ff000000018ea20");
    addData("3ff000012f2af5bf", "3ff000000026ce32");
    addData("3ff00001413724bf", "3ff0000000291d94");
    addData("3ff0000153bd4e6d", "3ff00000002b7c94");
    addData("3ff000015f75ad2a", "3ff00000002cfca0");
    addData("3ff0000198da81dd", "3ff000000034554e");
    addData("3ff00001b178bb95", "3ff0000000377bfc");
    addData("3ff00001ce277ce7", "3ff00000003b27dc");
    addData("3ff0000294b2be1a", "3ff00000005491bc");
    addData("3ff00002f453d343", "3ff000000060cf4e");
    addData("3ff000045a0b2035", "3ff00000008e98e6");
    addData("3ff000045d0cfef6", "3ff00000008efb72");
    addData("3ff000057b83c83f", "3ff0000000b3a640");
    addData("3ff00005980b881e", "3ff0000000b74d20");
    addData("3ff00005983e8eb0", "3ff0000000b753a8");
    addData("3ff00005c7692d61", "3ff0000000bd5d34");
    addData("3ff00006666f30ff", "3ff0000000d1b80e");
    addData("3ff0000736bc4e32", "3ff0000000ec61a0");
    addData("3ff000078694d925", "3ff0000000f699fe");
    addData("3ff0000965922b01", "3ff000000133e966");
    addData("3ff00009d3df2e9c", "3ff00000014207b4");
    addData("3ff0000a7fc1031c", "3ff00000015807e2");
    addData("3ff0000b24e284fd", "3ff00000016d2ad6");
    addData("3ff0000ba3ef2a27", "3ff00000017d6df2");
    addData("3ff0000cc84e613f", "3ff0000001a2da46");
    addData("3ff0000d3f510930", "3ff0000001b215f6");
    addData("3ff0000ed6a63814", "3ff0000001e63942");
    addData("3ff0000faaddda28", "3ff0000002016318");
    addData("3ff0000fca014900", "3ff0000002055f6c");
    addData("3ff00010e0b096e8", "3ff0000002290b3a");
    addData("3ff00011fc8e48d6", "3ff00000024d60ca");
    addData("3ff00013f7a36c74", "3ff00000028e4892");
    addData("3ff0001463399c1f", "3ff00000029c0de8");
    addData("3ff00015993ff1af", "3ff0000002c3bc98");
    addData("3ff00015c8f06afe", "3ff0000002c9d73e");
    addData("3ff00015ca4ae425", "3ff0000002ca0398");
    addData("3ff0001632cccf1b", "3ff0000002d76406");
    addData("3ff000163356b88b", "3ff0000002d775ac");
    addData("3ff00016f40da515", "3ff0000002f02068");
    addData("3ff00019f2cf3156", "3ff00000035244e2");
    addData("3ff0001a642c16b8", "3ff000000360c778");
    addData("3ff0001b5d3e01b7", "3ff000000380a8c8");
    addData("3ff0001c9734868b", "3ff0000003a8d872");
    addData("3ff0001d25c4a5a3", "3ff0000003bb17d2");
    addData("3ff0002175e6b56b", "3ff0000004486afa");
    addData("3ff00021d4f4d524", "3ff00000045495a4");
    addData("3ff00021f6080e3c", "3ff000000458d16a");
    addData("3ff00022373a14e0", "3ff00000046129aa");
    addData("3ff000228ada77a7", "3ff00000046bddda");
    addData("3ff0002514302bc2", "3ff0000004befa86");
    addData("3ff00028864aa46e", "3ff00000052fe238");
    addData("3ff0002cc166be3c", "3ff0000005ba841e");
    addData("3ff0002d663f7849", "3ff0000005cf9d94");
    addData("3ff00030ecbec180", "3ff0000006432148");
    addData("3ff00032bc7bd1fb", "3ff00000067e7c58");
    addData("3ff00033d5ab51c8", "3ff0000006a279c8");
    addData("3ff000342811fe2a", "3ff0000006ad05c4");
    addData("3ff0003757f8a604", "3ff00000071573d6");
    addData("3ff0003fd1c236d7", "3ff00000082b2b64");
    addData("3ff00040f8b820de", "3ff000000850ec12");
    addData("3ff00042afe6956a", "3ff0000008892244");
    addData("3ff000472fce0067", "3ff00000091c9160");
    addData("3ff000489c0f28bd", "3ff00000094b3072");
    addData("3ff00051c09cc796", "3ff000000a76c20e");
    addData("3ff00056dbfd9971", "3ff000000b1e16c8");
    addData("3ff00057dfaf3d77", "3ff000000b3f53b8");
    addData("3ff000591fba3154", "3ff000000b684a00");
    addData("3ff0005b7357c2d4", "3ff000000bb48572");
    addData("3ff0005db6c24365", "3ff000000bfeae14");
    addData("3ff00066e3aaf20c", "3ff000000d2b4fea");
    addData("3ff00067740a0dfe", "3ff000000d3dca36");
    addData("3ff00068def18101", "3ff000000d6c3cac");
    addData("3ff0006e631be9f7", "3ff000000e20f9c6");
    addData("3ff00077944ddc87", "3ff000000f4e26d2");
    addData("3ff0007e094cbb50", "3ff000001021b5d6");
    addData("3ff0007f93d4171e", "3ff0000010543432");
    addData("3ff0008b8fa77733", "3ff0000011dcd5e8");
    addData("3ff0008c9af73595", "3ff0000011ff0c00");
    addData("3ff000918683e6b9", "3ff0000012a03f4c");
    addData("3ff0009ea781c5dc", "3ff00000144e644e");
    addData("3ff000a1bfba1cad", "3ff0000014b3c7d0");
    addData("3ff000a2fd62cd32", "3ff0000014dc6f40");
    addData("3ff000a87b8e3d21", "3ff0000015906556");
    addData("3ff000a98a72819b", "3ff0000015b3107c");
    addData("3ff000ab7bfa76bc", "3ff0000015f2bcf0");
    addData("3ff000ab8a035de5", "3ff0000015f488c0");
    addData("3ff000b83b92666f", "3ff000001794683e");
    addData("3ff000bb1c2e1917", "3ff0000017f2ad26");
    addData("3ff000c0e8df0274", "3ff0000018b0aeb2");
    addData("3ff000c3dcd52810", "3ff0000019116d74");
    addData("3ff000c66ab34f1a", "3ff0000019651b5e");
    addData("3ff000c6c0333d8d", "3ff0000019700c7e");
    addData("3ff000da5dac4a20", "3ff000001bf2ab68");
    addData("3ff000dad9042c59", "3ff000001c027448");
    addData("3ff000def05fa9c8", "3ff000001c887cdc");
    addData("3ff000e7d0d1886a", "3ff000001dab4c32");
    addData("3ff000ee89e5c6e1", "3ff000001e878be0");
    addData("3ff000efedd98c6e", "3ff000001eb51910");
    addData("3ff000f2785d7ac7", "3ff000001f085840");
    addData("3ff0010403f34767", "3ff0000021472146");
    addData("3ff0010cd9aeb281", "3ff0000022688f08");
    addData("3ff00121ef66cfaf", "3ff00000251b4868");
    addData("3ff0012e43815868", "3ff0000026af266e");
    addData("3ff0012eaddc6e6c", "3ff0000026bcc27e");
    addData("3ff001342f8076a1", "3ff0000027712428");
    addData("3ff0013bca543227", "3ff00000286a42d2");
    addData("3ff0013c2556de0d", "3ff000002875e826");
    addData("3ff00142945996d8", "3ff000002948a8f8");
    addData("3ff0014b030edd2b", "3ff000002a5ce37e");
    addData("3ff00152d0067fdc", "3ff000002b5c6b3c");
    addData("3ff00157b53c4b1b", "3ff000002bfcc712");
    addData("3ff0015c35ba4a3e", "3ff000002c903f7c");
    addData("3ff0016e89d3a3d4", "3ff000002ee8a1d4");
    addData("3ff0019142307bb4", "3ff000003359ed28");
    addData("3ff0019b46fd6dd3", "3ff0000034a21802");
    addData("3ff001a09de2304b", "3ff000003550fcb6");
    addData("3ff001aa5e824b31", "3ff0000036906d46");
    addData("3ff001d58a281371", "3ff000003c166f1a");
    addData("3ff001eb2c463f76", "3ff000003edafd18");
    addData("3ff001ed83fcb397", "3ff000003f27b742");
    addData("3ff00208430b92aa", "3ff000004293b74e");
    addData("3ff0020e7a8cf479", "3ff00000435f53c0");
    addData("3ff0021a2f14a0ee", "3ff0000044deb040");
    addData("3ff00225d2bdab65", "3ff00000465be328");
    addData("3ff0023b7ef88d11", "3ff000004921ae72");
    addData("3ff0025118554352", "3ff000004be50c14");
    addData("3ff0026155666d19", "3ff000004df8d8d4");
    addData("3ff002741437f128", "3ff00000505ebbd4");
    addData("3ff00277c54a46dc", "3ff0000050d7a15a");
    addData("3ff0027e7383d6a3", "3ff0000051b26818");
    addData("3ff002c4fc4fdc0a", "3ff000005ab8317e");
    addData("3ff002c85c832a94", "3ff000005b26bc70");
    addData("3ff002cb8db1cdd2", "3ff000005b8f43a8");
    addData("3ff002ff8dc81d17", "3ff0000062360202");
    addData("3ff00307dd1df5e8", "3ff0000063461b4c");
    addData("3ff00314b1e73ecf", "3ff0000064ea3ef8");
    addData("3ff0032fda05447d", "3ff0000068636fe0");
    addData("3ff003443fe32ca3", "3ff000006aff4f62");
    addData("3ff00358e0494db1", "3ff000006da2a7fc");
    addData("3ff0035d6b21692b", "3ff000006e3760e2");
    addData("3ff00364ba163146", "3ff000006f26a9dc");
    addData("3ff00370ee36a27f", "3ff0000070b637f4");
    addData("3ff0039d1f6e2a69", "3ff00000765d10a0");
    addData("3ff003f5ccdc2a0e", "3ff0000081b42a4c");
    addData("3ff0040f75988e6a", "3ff0000084fc2434");
    addData("3ff0041cf1d045a6", "3ff0000086b595c6");
    addData("3ff0041d9e7db622", "3ff0000086cbaa66");
    addData("3ff0043174f1cecf", "3ff0000089550ca2");
    addData("3ff00435791bd0f7", "3ff0000089d88502");
    addData("3ff00466bb29aef9", "3ff000009024e9a4");
    addData("3ff004824e602aa4", "3ff0000093ab82ba");
    addData("3ff0048e35a7cb3d", "3ff00000953121ae");
    addData("3ff0048fbb17c5da", "3ff000009562ec96");
    addData("3ff004901a2243db", "3ff00000956f136c");
    addData("3ff00496fe59bc98", "3ff000009650a4ca");
    addData("3ff004a6d1ff4ea8", "3ff000009856aba0");
    addData("3ff004b668c99125", "3ff000009a54e898");
    addData("3ff005033e4be951", "3ff00000a4279f34");
    addData("3ff005468a327822", "3ff00000acc20750");
    addData("3ff005583aa2b489", "3ff00000af04eb28");
    addData("3ff0058e587f1f45", "3ff00000b5efdb84");
    addData("3ff00594e6478777", "3ff00000b6c6527e");
    addData("3ff005c517af10c9", "3ff00000bcef536c");
    addData("3ff005fd34c3ed15", "3ff00000c41b6c0c");
    addData("3ff00605a05f9aa0", "3ff00000c52eefa6");
    addData("3ff00659bb10a908", "3ff00000cfeeb5f0");
    addData("3ff0068cd52978ae", "3ff00000d676966c");
    addData("3ff006e4da5039f7", "3ff00000e1b61b90");
    addData("3ff006e82e891fef", "3ff00000e223023e");
    addData("3ff006ea73f88946", "3ff00000e26d4ea2");
    addData("3ff006f9bbd18d8d", "3ff00000e4612d2c");
    addData("3ff0071031085c9b", "3ff00000e73fd148");
    addData("3ff007183010ac0b", "3ff00000e84562c6");
    addData("3ff0076b514de586", "3ff00000f2e491be");
    addData("3ff007a23c6d5b72", "3ff00000f9e8d688");
    addData("3ff007ba0d6b9092", "3ff00000fcf3cb14");
    addData("3ff00818a10a8fa7", "3ff000010908e8cc");
    addData("3ff0081d9a5961f3", "3ff0000109ab922e");
    addData("3ff00842204a3715", "3ff000010e55fa30");
    addData("3ff0088ee1bcbc5c", "3ff000011823f4e8");
    addData("3ff008feca56dfb2", "3ff00001266f2416");
    addData("3ff009153b12f7fb", "3ff00001294cd942");
    addData("3ff0091653a67e2a", "3ff000012970aed8");
    addData("3ff0091e30077029", "3ff000012a71b252");
    addData("3ff0091e8cbf5d10", "3ff000012a7d89c6");
    addData("3ff00939e8deaf04", "3ff000012dfc1048");
    addData("3ff0094792ff27e8", "3ff000012fbad0ac");
    addData("3ff009645e4bb389", "3ff0000133683264");
    addData("3ff009b0b2616930", "3ff000013d27849e");
    addData("3ff009b9e70187a2", "3ff000013e547404");
    addData("3ff009de0cb6ef67", "3ff0000142f21a62");
    addData("3ff009ea31bdbe75", "3ff00001447f19aa");
    addData("3ff009f707e9a97b", "3ff000014622b662");
    addData("3ff009fb36d3c998", "3ff0000146ab74ec");
    addData("3ff00a28820b8780", "3ff000014c7401ae");
    addData("3ff00a3632db72be", "3ff000014e3382a6");
    addData("3ff00a37999dc7cf", "3ff000014e61513c");
    addData("3ff00aa7c8696175", "3ff000015cb3fc44");
    addData("3ff00abd92d5068e", "3ff000015f7c2a02");
    addData("3ff00aed7ecfd407", "3ff00001659a5334");
    addData("3ff00b1fa2b4a6a9", "3ff000016c00e92e");
    addData("3ff00b2d0eb64e9f", "3ff000016db786be");
    addData("3ff00b69e36600ef", "3ff00001757b59f8");
    addData("3ff00bef8115b65d", "3ff0000186893de0");
    addData("3ff00bf4c7765387", "3ff000018735966e");
    addData("3ff00bfe8ddc73ff", "3ff000018874f61e");
    addData("3ff00c15de2b0d5e", "3ff000018b6eaab6");
    addData("3ff00c7dd4479905", "3ff0000198b316ac");
    addData("3ff00d276f3b5ce5", "3ff00001ae576102");
    addData("3ff00d27ce901c9a", "3ff00001ae638ad2");
    addData("3ff00d67c2b33446", "3ff00001b68c6810");
    addData("3ff00dafadce5c27", "3ff00001bfb9439e");
    addData("3ff00dcf80fe76bb", "3ff00001c3c89e06");
    addData("3ff00e00380e10d7", "3ff00001c9ff83c8");
    addData("3ff00e5ccd725b39", "3ff00001d5ced49a");
    addData("3ff00e86a21944c7", "3ff00001db24b940");
    addData("3ff00e86a7859088", "3ff00001db256a52");
    addData("3ff00eb271b30fab", "3ff00001e0bb385a");
    addData("3ff00f01ca36b0b7", "3ff00001ead9d548");
    addData("3ff00f3fdf937c0f", "3ff00001f2c4b382");
    addData("3ff00f83f7bdfa67", "3ff00001fb73ac9e");
    addData("3ff0103649af599a", "3ff00002123055bc");
    addData("3ff010e5e83c7501", "3ff000022893e022");
    addData("3ff01146bafecacc", "3ff0000234eb6822");
    addData("3ff011b575f20dec", "3ff00002430864f4");
    addData("3ff011c0fef22410", "3ff000024480bffe");
    addData("3ff011cd94e46714", "3ff00002461b60b0");
    addData("3ff0123e52985644", "3ff0000254797fd0");
    addData("3ff0124f4152a403", "3ff0000256a1e184");
    addData("3ff0126d052860e2", "3ff000025a6cde26");
    addData("3ff01270a65c85d5", "3ff000025ae345f4");
    addData("3ff012c4fd4385cd", "3ff0000265a26b38");
    addData("3ff012d826f868c8", "3ff0000268137b10");
    addData("3ff0131d07497794", "3ff0000270da035c");
    addData("3ff01340687303d7", "3ff00002755bedb6");
    addData("3ff01349f3ac164b", "3ff000027693328a");
    addData("3ff0142094f13c33", "3ff0000291ea8804");
    addData("3ff014797ba4c0ef", "3ff000029d3d4906");
    addData("3ff014b591802818", "3ff00002a4e451b6");
    addData("3ff014cf1b9413aa", "3ff00002a824f9aa");
    addData("3ff014da477e1774", "3ff00002a9913162");
    addData("3ff0150325b205a6", "3ff00002aec58ca0");
    addData("3ff01545322f876f", "3ff00002b72eba66");
    addData("3ff015cba20ec276", "3ff00002c84cef0e");
    addData("3ff016571b207ee8", "3ff00002da0eb434");
    addData("3ff016d3dfdc8cc0", "3ff00002e9f0b5ea");
    addData("3ff01743c6cc53ca", "3ff00002f82f0348");
    addData("3ff0177da6425b0e", "3ff00002ff8ca296");
    addData("3ff01789d91cbe83", "3ff00003011a146c");
    addData("3ff017ac17ac002d", "3ff000030575c5da");
    addData("3ff0189be8b70297", "3ff0000323fa0fe8");
    addData("3ff018abdf39553b", "3ff0000326020250");
    addData("3ff0192278704be3", "3ff000033518c576");
    addData("3ff019be4095d6ae", "3ff0000348e9f02a");
    addData("3ff019ce7bda4503", "3ff000034afa7cee");
    addData("3ff01a4586c04fe7", "3ff000035a1ea55a");
    addData("3ff01b15d687a1cf", "3ff00003749c73ac");
    addData("3ff01bec8f058641", "3ff000038fe982c2");
    addData("3ff01c1791d40d0a", "3ff00003956153b8");
    addData("3ff01c1d9e395eba", "3ff0000396262bf8");
    addData("3ff01d11a2a555de", "3ff00003b52aba6c");
    addData("3ff01d1c6f0356e4", "3ff00003b68a12bc");
    addData("3ff01d2883db0d7b", "3ff00003b8132994");
    addData("3ff01e71e773ffc2", "3ff00003e1eea894");
    addData("3ff01f434d3c5e64", "3ff00003fc88ecc0");
    addData("3ff01f6112a7fe03", "3ff0000400511134");
    addData("3ff020fb74e9f170", "3ff00004346fbfa2");
    addData("3ff021a0782fbc23", "3ff0000449634c62");
    addData("3ff0232e074df506", "3ff000047bda0f6c");
    addData("3ff02414e9f5c03b", "3ff0000499267fd0");
    addData("3ff02430709f51ec", "3ff000049ca49698");
    addData("3ff024c8eb334fe8", "3ff00004affcebd4");
    addData("3ff02505e61f7b8d", "3ff00004b7b94920");
    addData("3ff025cadab76a1f", "3ff00004d0b4be2e");
    addData("3ff025e4a878d29f", "3ff00004d3fa8b08");
    addData("3ff0262e9bbe0441", "3ff00004dd5b75f0");
    addData("3ff0270f120aff91", "3ff00004f9d1f76e");
    addData("3ff02754e840e5b2", "3ff0000502acb29a");
    addData("3ff02c36701515db", "3ff00005a1002d44");
    addData("3ff032d08c7e2e21", "3ff0000676db002c");
    addData("3ff035a55ff1c78a", "3ff00006d27732bc");
    addData("3ff03644918d0785", "3ff00006e693debe");
    addData("3ff037bb1dedc8cd", "3ff0000715e28914");
    addData("3ff0395604cd3567", "3ff0000749c3c032");
    addData("3ff039bf04e42f96", "3ff000075704c2c6");
    addData("3ff03e7bdeeecc32", "3ff00007f004ed7a");
    addData("3ff03ed24e556ded", "3ff00007faea95ee");
    addData("3ff03ef1c681f0bd", "3ff00007fee23120");
    addData("3ff03fcb5661fa8f", "3ff000081a4eb43e");
    addData("3ff042d910585bee", "3ff000087ccc6c26");
    addData("3ff0450fda5471a3", "3ff00008c42a03da");
    addData("3ff04641fc8b11ca", "3ff00008eab1bc98");
    addData("3ff047a41ad06417", "3ff0000917400204");
    addData("3ff04db90c5c127d", "3ff00009daf8d8ee");
    addData("3ff04f9e73a25ac8", "3ff0000a17eef48e");
    addData("3ff04fcbf8abc9fd", "3ff0000a1da61602");
    addData("3ff0511862bf450d", "3ff0000a4760eee0");
    addData("3ff051210e0bfeb6", "3ff0000a48777d74");
    addData("3ff055982b0f8b04", "3ff0000ad7de456c");
    addData("3ff05727a94317f9", "3ff0000b09f2a4ac");
    addData("3ff05e9f2de8eae8", "3ff0000bf9504088");
    addData("3ff066a0bcc57b6b", "3ff0000cf97de4b2");
    addData("3ff068689beee8b2", "3ff0000d3267db9a");
    addData("3ff06c311b09de1e", "3ff0000dab3d2c1e");
    addData("3ff06fbdc15a990b", "3ff0000e1c81b8d0");
    addData("3ff07003413b4e40", "3ff0000e252a9014");
    addData("3ff0707b97ed6426", "3ff0000e3428b132");
    addData("3ff077df0910b501", "3ff0000f1f96de9e");
    addData("3ff07f468476cd37", "3ff000100b1bf6fa");
    addData("3ff08da4fce37b7d", "3ff00011d2fefcd4");
    addData("3ff08ee23b88ee24", "3ff00011fa3db176");
    addData("3ff0907c57df2523", "3ff000122cf4fcd0");
    addData("3ff09d1632ec4b9a", "3ff00013bb369796");
    addData("3ff09e92f87b35af", "3ff00013ea2501a4");
    addData("3ff0a900bef42c6d", "3ff0001532be2c26");
    addData("3ff0b33bfdb9a6c8", "3ff000167457a5ce");
    addData("3ff0b385e0945b49", "3ff000167d6737f2");
    addData("3ff0c1dd413c5db1", "3ff000183edd3e2a");
    addData("3ff0c68383edcd8b", "3ff00018d041d5b4");
    addData("3ff0d0d3019dddb8", "3ff0001a121dc50e");
    addData("3ff0dd223ceffaf9", "3ff0001b915e94ac");
    addData("3ff0ef73f1a9aa75", "3ff0001dc9b6eb1a");
    addData("3ff15e3c3258a73d", "3ff0002b045ced4c");
    addData("3ff29c4ea7efe276", "3ff0004f3e9a3c94");
    addData("3ff2e90398bec6e7", "3ff000579e8208fa");
    addData("3ff318ed7b598d20", "3ff0005cc8807672");
    addData("3ff348ec880ef24d", "3ff00061e7db55c4");
    addData("3ff37391b11ff076", "3ff000666a5c8088");
    addData("3ff515fde2e99b0d", "3ff00090b8cf57de");
    addData("3ff6a89b5ffae2dd", "3ff000b67158530a");
    addData("3ff99efec0fbc5d8", "3ff000f6e0b478fe");
    addData("3ffffcf9acb020be", "3ff0016b472e0602");
    addData("3ff0010b331a0d17", "3ff0000022327b72");
    addData("3ff0016a01a8e426", "3ff000002e542eac");
  }

  private static void addData(String a, String x87Ret) {
    data.put(hexToDouble(a), hexToDouble(x87Ret));
  }

  static double hexToDouble(String input) {
    // Convert the hex string to a long
    long hexAsLong = Long.parseLong(input, 16);
    // and then convert the long to a double
    return Double.longBitsToDouble(hexAsLong);
  }
}