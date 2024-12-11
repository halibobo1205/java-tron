package org.tron.common.math;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class StrictMathWrapper {

  private static final Map<PowData, Double> powData = Collections.synchronizedMap(new HashMap<>());
  private static final String POW_B1 = "3f40624dd2f1a9fc"; // 1/2000 = 0.0005
  private static final String POW_B2 = "409f400000000000"; //

  public static double pow(double a, double b) {
    double strictResult = StrictMath.pow(a, b);
    return powData.getOrDefault(new PowData(a, b), strictResult);
  }

  /**
   * This static block is used to initialize the data map.
   */
  static {
    /*
    addPowData("3ff000000669e439", POW_B1, "3ff000000000d22a"); //  6054619
    addPowData("3ff000000b4fe3ac", POW_B1, "3ff00000000172ac"); //  4445624
    addPowData("3ff0000015d11937", POW_B1, "3ff000000002cae4"); //  4067205
    addPowData("3ff000001adebd1f", POW_B1, "3ff000000003707a"); //  4189742
    addPowData("3ff000002fc6a33f", POW_B1, "3ff0000000061d86"); //  4065476
    addPowData("3ff0000046d74585", POW_B1, "3ff0000000091150"); //  6279093
    addPowData("3ff000005454cb56", POW_B1, "3ff00000000acb5e"); //  5296430
    addPowData("3ff000005d4df1d8", POW_B1, "3ff00000000bf166"); //  4589315
    addPowData("3ff0000071929b3b", POW_B1, "3ff00000000e898c"); //  7934922
    addPowData("3ff00000a9d6d98d", POW_B1, "3ff000000015bd4a"); //  5396077
    addPowData("3ff00000c2a51ab7", POW_B1, "3ff000000018ea20"); //  5098864
    addPowData("3ff000012f2af5bf", POW_B1, "3ff000000026ce32"); //  4301655
    addPowData("3ff00001413724bf", POW_B1, "3ff0000000291d94"); //  4473824
    addPowData("3ff0000153bd4e6d", POW_B1, "3ff00000002b7c94"); //  5050070
    addPowData("3ff000015f75ad2a", POW_B1, "3ff00000002cfca0"); //  8666009
    addPowData("3ff0000198da81dd", POW_B1, "3ff000000034554e"); //  6911745
    addPowData("3ff00001b178bb95", POW_B1, "3ff0000000377bfc"); //  5575744
    addPowData("3ff00001ce277ce7", POW_B1, "3ff00000003b27dc"); //  5139389
    addPowData("3ff0000294b2be1a", POW_B1, "3ff00000005491bc"); //  6279746
    addPowData("3ff00002f453d343", POW_B1, "3ff000000060cf4e"); //  4533215
    addPowData("3ff000045a0b2035", POW_B1, "3ff00000008e98e6"); //  5295829
    addPowData("3ff000045d0cfef6", POW_B1, "3ff00000008efb72"); //  5050393
    addPowData("3ff00004e5545390", POW_B1, "3ff0000000a06d00"); //  23707164
    addPowData("3ff00004ec241f48", POW_B2, "3ff026a33089cf0d"); //  3923215,5139856
    addPowData("3ff000057b83c83f", POW_B1, "3ff0000000b3a640"); //  9631452
    addPowData("3ff00005980b881e", POW_B1, "3ff0000000b74d20"); //  5120211
    addPowData("3ff00005983e8eb0", POW_B1, "3ff0000000b753a8"); //  9341478
    addPowData("3ff00005c7692d61", POW_B1, "3ff0000000bd5d34"); //  4499056
    addPowData("3ff00006666f30ff", POW_B1, "3ff0000000d1b80e"); //  5185021
    addPowData("3ff0000736bc4e32", POW_B1, "3ff0000000ec61a0"); //  4044694
    addPowData("3ff000078694d925", POW_B1, "3ff0000000f699fe"); //  5440505
    addPowData("3ff0000965922b01", POW_B1, "3ff000000133e966"); //  4490332
    addPowData("3ff00009d3df2e9c", POW_B1, "3ff00000014207b4"); //  7675535
    addPowData("3ff0000a7fc1031c", POW_B1, "3ff00000015807e2"); //  4189823
    addPowData("3ff0000b24e284fd", POW_B1, "3ff00000016d2ad6"); //  4052870
    addPowData("3ff0000ba3ef2a27", POW_B1, "3ff00000017d6df2"); //  8295434
    addPowData("3ff0000cc84e613f", POW_B1, "3ff0000001a2da46"); //  9269124
    addPowData("3ff0000d3f510930", POW_B1, "3ff0000001b215f6"); //  8481548
    addPowData("3ff0000ed6a63814", POW_B1, "3ff0000001e63942"); //  6497507
    addPowData("3ff0000faaddda28", POW_B1, "3ff0000002016318"); //  5819388
    addPowData("3ff0000fca014900", POW_B1, "3ff0000002055f6c"); //  8139429
    addPowData("3ff00010e0b096e8", POW_B1, "3ff0000002290b3a"); //  5763266
    addPowData("3ff00011fc8e48d6", POW_B1, "3ff00000024d60ca"); //  8639310
    addPowData("3ff00013f7a36c74", POW_B1, "3ff00000028e4892"); //  4149434
    addPowData("3ff0001463399c1f", POW_B1, "3ff00000029c0de8"); //  6682410
    addPowData("3ff00015993ff1af", POW_B1, "3ff0000002c3bc98"); //  4212717
    addPowData("3ff00015c8f06afe", POW_B1, "3ff0000002c9d73e"); //  4793587
    addPowData("3ff00015ca4ae425", POW_B1, "3ff0000002ca0398"); //  5216552
    addPowData("3ff0001632cccf1b", POW_B1, "3ff0000002d76406"); //  4405788
    addPowData("3ff000163356b88b", POW_B1, "3ff0000002d775ac"); //  8475699
    addPowData("3ff00016f40da515", POW_B1, "3ff0000002f02068"); //  4426368
    addPowData("3ff00019f2cf3156", POW_B1, "3ff00000035244e2"); //  5548252
    addPowData("3ff0001a642c16b8", POW_B1, "3ff000000360c778"); //  4427218
    addPowData("3ff0001b5d3e01b7", POW_B1, "3ff000000380a8c8"); //  8410841
    addPowData("3ff0001c9734868b", POW_B1, "3ff0000003a8d872"); //  4228184
    addPowData("3ff0001d25c4a5a3", POW_B1, "3ff0000003bb17d2"); //  4328945
    addPowData("3ff0002175e6b56b", POW_B1, "3ff0000004486afa"); //  5775091
    addPowData("3ff00021d4f4d524", POW_B1, "3ff00000045495a4"); //  4650100
    addPowData("3ff00021f6080e3c", POW_B1, "3ff000000458d16a"); //  7092933
    addPowData("3ff00022373a14e0", POW_B1, "3ff00000046129aa"); //  4103540
    addPowData("3ff000228ada77a7", POW_B1, "3ff00000046bddda"); //  7646942
    addPowData("3ff0002514302bc2", POW_B1, "3ff0000004befa86"); //  5413457
    addPowData("3ff00028864aa46e", POW_B1, "3ff00000052fe238"); //  10493375
    addPowData("3ff0002cc166be3c", POW_B1, "3ff0000005ba841e"); //  8763101
    addPowData("3ff0002d663f7849", POW_B1, "3ff0000005cf9d94"); //  4165938
    addPowData("3ff00030ecbec180", POW_B1, "3ff0000006432148"); //  6563071
    addPowData("3ff00032bc7bd1fb", POW_B1, "3ff00000067e7c58"); //  4089949
    addPowData("3ff00033d5ab51c8", POW_B1, "3ff0000006a279c8"); //  6240974
    addPowData("3ff000342811fe2a", POW_B1, "3ff0000006ad05c4"); //  4804319
    addPowData("3ff0003757f8a604", POW_B1, "3ff00000071573d6"); //  4414162
    addPowData("3ff0003fd1c236d7", POW_B1, "3ff00000082b2b64"); //  4915375
    addPowData("3ff00040f8b820de", POW_B1, "3ff000000850ec12"); //  5556095
    addPowData("3ff00042afe6956a", POW_B1, "3ff0000008892244"); //  5864127
    addPowData("3ff000472fce0067", POW_B1, "3ff00000091c9160"); //  5120934
    addPowData("3ff000489c0f28bd", POW_B1, "3ff00000094b3072"); //  7112412
    addPowData("3ff00051c09cc796", POW_B1, "3ff000000a76c20e"); //  4166806
    addPowData("3ff00056dbfd9971", POW_B1, "3ff000000b1e16c8"); //  4871545
    addPowData("3ff00057dfaf3d77", POW_B1, "3ff000000b3f53b8"); //  4218139
    addPowData("3ff000591fba3154", POW_B1, "3ff000000b684a00"); //  4244990
    addPowData("3ff0005b7357c2d4", POW_B1, "3ff000000bb48572"); //  6167339
    addPowData("3ff0005db6c24365", POW_B1, "3ff000000bfeae14"); //  5761870
    addPowData("3ff00066e3aaf20c", POW_B1, "3ff000000d2b4fea"); //  7030265
    addPowData("3ff00067740a0dfe", POW_B1, "3ff000000d3dca36"); //  8516287
    addPowData("3ff00068def18101", POW_B1, "3ff000000d6c3cac"); //  4801947
    addPowData("3ff0006e631be9f7", POW_B1, "3ff000000e20f9c6"); //  5085593
    addPowData("3ff00077944ddc87", POW_B1, "3ff000000f4e26d2"); //  5921244
    addPowData("3ff0007e094cbb50", POW_B1, "3ff000001021b5d6"); //  5009106
    addPowData("3ff0007f93d4171e", POW_B1, "3ff0000010543432"); //  4143171
    addPowData("3ff0008b8fa77733", POW_B1, "3ff0000011dcd5e8"); //  4581139
    addPowData("3ff0008c9af73595", POW_B1, "3ff0000011ff0c00"); //  8033889
    addPowData("3ff000918683e6b9", POW_B1, "3ff0000012a03f4c"); //  4848145
    addPowData("3ff0009ea781c5dc", POW_B1, "3ff00000144e644e"); //  5213378
    addPowData("3ff000a1bfba1cad", POW_B1, "3ff0000014b3c7d0"); //  7667880
    addPowData("3ff000a2fd62cd32", POW_B1, "3ff0000014dc6f40"); //  6367165
    addPowData("3ff000a87b8e3d21", POW_B1, "3ff0000015906556"); //  7492633
    addPowData("3ff000a98a72819b", POW_B1, "3ff0000015b3107c"); //  5136997
    addPowData("3ff000ab7bfa76bc", POW_B1, "3ff0000015f2bcf0"); //  7335898
    addPowData("3ff000ab8a035de5", POW_B1, "3ff0000015f488c0"); //  4245180
    addPowData("3ff000b83b92666f", POW_B1, "3ff000001794683e"); //  4277187
    addPowData("3ff000bb1c2e1917", POW_B1, "3ff0000017f2ad26"); //  4167516
    addPowData("3ff000c0e8df0274", POW_B1, "3ff0000018b0aeb2"); //  4771494
    addPowData("3ff000c3dcd52810", POW_B1, "3ff0000019116d74"); //  6743204
    addPowData("3ff000c66ab34f1a", POW_B1, "3ff0000019651b5e"); //  4790987
    addPowData("3ff000c6c0333d8d", POW_B1, "3ff0000019700c7e"); //  5425345
    addPowData("3ff000da5dac4a20", POW_B1, "3ff000001bf2ab68"); //  4953040
    addPowData("3ff000dad9042c59", POW_B1, "3ff000001c027448"); //  4150083
    addPowData("3ff000def05fa9c8", POW_B1, "3ff000001c887cdc"); //  7860324
    addPowData("3ff000df8a0eadf8", POW_B2, "3ff8817d68bc901b"); //  3922438
    addPowData("3ff000e7d0d1886a", POW_B1, "3ff000001dab4c32"); //  4110653
    addPowData("3ff000ee89e5c6e1", POW_B1, "3ff000001e878be0"); //  4564217
    addPowData("3ff000efedd98c6e", POW_B1, "3ff000001eb51910"); //  4170159
    addPowData("3ff000f2785d7ac7", POW_B1, "3ff000001f085840"); //  4842514
    addPowData("3ff0010403f34767", POW_B1, "3ff0000021472146"); //  6428736
    addPowData("3ff0010b331a0d17", POW_B1, "3ff0000022327b72"); //  11029906
    addPowData("3ff0010cd9aeb281", POW_B1, "3ff0000022688f08"); //  5882468
    addPowData("3ff00121ef66cfaf", POW_B1, "3ff00000251b4868"); //  4611349
    addPowData("3ff0012e43815868", POW_B1, "3ff0000026af266e"); //  6555029
    addPowData("3ff0012eaddc6e6c", POW_B1, "3ff0000026bcc27e"); //  4139460
    addPowData("3ff001342f8076a1", POW_B1, "3ff0000027712428"); //  4406202
    addPowData("3ff0013bca543227", POW_B1, "3ff00000286a42d2"); //  8292427
    addPowData("3ff0013c2556de0d", POW_B1, "3ff000002875e826"); //  4645996
    addPowData("3ff00142945996d8", POW_B1, "3ff000002948a8f8"); //  4608897
    addPowData("3ff0014b030edd2b", POW_B1, "3ff000002a5ce37e"); //  4493225
    addPowData("3ff00152d0067fdc", POW_B1, "3ff000002b5c6b3c"); //  4674708
    addPowData("3ff00157b53c4b1b", POW_B1, "3ff000002bfcc712"); //  9057977
    addPowData("3ff0015c35ba4a3e", POW_B1, "3ff000002c903f7c"); //  4349043
    addPowData("3ff0016a01a8e426", POW_B1, "3ff000002e542eac"); //  11349035
    addPowData("3ff0016e89d3a3d4", POW_B1, "3ff000002ee8a1d4"); //  4561283
    addPowData("3ff0019142307bb4", POW_B1, "3ff000003359ed28"); //  5942494
    addPowData("3ff0019b46fd6dd3", POW_B1, "3ff0000034a21802"); //  5118996
    addPowData("3ff001a09de2304b", POW_B1, "3ff000003550fcb6"); //  8945297
    addPowData("3ff001aa5e824b31", POW_B1, "3ff0000036906d46"); //  4824741
    addPowData("3ff001d58a281371", POW_B1, "3ff000003c166f1a"); //  4582179,6799343
    addPowData("3ff001eb2c463f76", POW_B1, "3ff000003edafd18"); //  4403798
    addPowData("3ff001ed83fcb397", POW_B1, "3ff000003f27b742"); //  4189345
    addPowData("3ff00208430b92aa", POW_B1, "3ff000004293b74e"); //  4352938
    addPowData("3ff0020e7a8cf479", POW_B1, "3ff00000435f53c0"); //  7526692
    addPowData("3ff0021a2f14a0ee", POW_B1, "3ff0000044deb040"); //  8517311
    addPowData("3ff00225d2bdab65", POW_B1, "3ff00000465be328"); //  5852046
    addPowData("3ff0023b7ef88d11", POW_B1, "3ff000004921ae72"); //  5358794
    addPowData("3ff0025118554352", POW_B1, "3ff000004be50c14"); //  8738093
    addPowData("3ff0026155666d19", POW_B1, "3ff000004df8d8d4"); //  6659044
    addPowData("3ff002741437f128", POW_B1, "3ff00000505ebbd4"); //  4857930
    addPowData("3ff00277c54a46dc", POW_B1, "3ff0000050d7a15a"); //  4528403
    addPowData("3ff0027e7383d6a3", POW_B1, "3ff0000051b26818"); //  4989564
    addPowData("3ff002c4fc4fdc0a", POW_B1, "3ff000005ab8317e"); //  5120546
    addPowData("3ff002c85c832a94", POW_B1, "3ff000005b26bc70"); //  4835604
    addPowData("3ff002cb8db1cdd2", POW_B1, "3ff000005b8f43a8"); //  4648021
    addPowData("3ff002ff8dc81d17", POW_B1, "3ff0000062360202"); //  4415724
    addPowData("3ff00307dd1df5e8", POW_B1, "3ff0000063461b4c"); //  5784744
    addPowData("3ff00314b1e73ecf", POW_B1, "3ff0000064ea3ef8"); //  4071538
    addPowData("3ff0032fda05447d", POW_B1, "3ff0000068636fe0"); //  4123826
    addPowData("3ff003443fe32ca3", POW_B1, "3ff000006aff4f62"); //  5910597
    addPowData("3ff00358e0494db1", POW_B1, "3ff000006da2a7fc"); //  4817093
    addPowData("3ff0035d6b21692b", POW_B1, "3ff000006e3760e2"); //  5902935
    addPowData("3ff00364ba163146", POW_B1, "3ff000006f26a9dc"); //  4257157
    addPowData("3ff00370ee36a27f", POW_B1, "3ff0000070b637f4"); //  6430235
    addPowData("3ff0039d1f6e2a69", POW_B1, "3ff00000765d10a0"); //  5106100
    addPowData("3ff003f5ccdc2a0e", POW_B1, "3ff0000081b42a4c"); //  4719113
    addPowData("3ff0040f75988e6a", POW_B1, "3ff0000084fc2434"); //  4700832
    addPowData("3ff0041cf1d045a6", POW_B1, "3ff0000086b595c6"); //  6897851
    addPowData("3ff0041d9e7db622", POW_B1, "3ff0000086cbaa66"); //  5070555
    addPowData("3ff0043174f1cecf", POW_B1, "3ff0000089550ca2"); //  8837430
    addPowData("3ff00435791bd0f7", POW_B1, "3ff0000089d88502"); //  5332291
    addPowData("3ff00466bb29aef9", POW_B1, "3ff000009024e9a4"); //  4718942
    addPowData("3ff004824e602aa4", POW_B1, "3ff0000093ab82ba"); //  4438653
    addPowData("3ff0048e35a7cb3d", POW_B1, "3ff00000953121ae"); //  5113293
    addPowData("3ff0048fbb17c5da", POW_B1, "3ff000009562ec96"); //  5006065
    addPowData("3ff004901a2243db", POW_B1, "3ff00000956f136c"); //  6659835
    addPowData("3ff00496fe59bc98", POW_B1, "3ff000009650a4ca"); //  6432355,6493373
    addPowData("3ff004a6d1ff4ea8", POW_B1, "3ff000009856aba0"); //  4090343
    addPowData("3ff004b668c99125", POW_B1, "3ff000009a54e898"); //  4684110
    addPowData("3ff005033e4be951", POW_B1, "3ff00000a4279f34"); //  6590692
    addPowData("3ff005468a327822", POW_B1, "3ff00000acc20750"); //  5151258
    addPowData("3ff005583aa2b489", POW_B1, "3ff00000af04eb28"); //  5752105
    addPowData("3ff0058e587f1f45", POW_B1, "3ff00000b5efdb84"); //  4861033
    addPowData("3ff00594e6478777", POW_B1, "3ff00000b6c6527e"); //  4070218
    addPowData("3ff005c517af10c9", POW_B1, "3ff00000bcef536c"); //  4837566
    addPowData("3ff005fd34c3ed15", POW_B1, "3ff00000c41b6c0c"); //  5887243
    addPowData("3ff00605a05f9aa0", POW_B1, "3ff00000c52eefa6"); //  5213985
    addPowData("3ff00659bb10a908", POW_B1, "3ff00000cfeeb5f0"); //  5514979
    addPowData("3ff0068cd52978ae", POW_B1, "3ff00000d676966c"); //  4109544
    addPowData("3ff006e4da5039f7", POW_B1, "3ff00000e1b61b90"); //  6476197
    addPowData("3ff006e82e891fef", POW_B1, "3ff00000e223023e"); //  4489203
    addPowData("3ff006ea73f88946", POW_B1, "3ff00000e26d4ea2"); //  4647814
    addPowData("3ff006f9bbd18d8d", POW_B1, "3ff00000e4612d2c"); //  4343910
    addPowData("3ff0071031085c9b", POW_B1, "3ff00000e73fd148"); //  4096051
    addPowData("3ff007183010ac0b", POW_B1, "3ff00000e84562c6"); //  4595504
    addPowData("3ff0076b514de586", POW_B1, "3ff00000f2e491be"); //  4795616
    addPowData("3ff007a23c6d5b72", POW_B1, "3ff00000f9e8d688"); //  4584905
    addPowData("3ff007ba0d6b9092", POW_B1, "3ff00000fcf3cb14"); //  6207589
    addPowData("3ff00818a10a8fa7", POW_B1, "3ff000010908e8cc"); //  4990879
    addPowData("3ff0081d9a5961f3", POW_B1, "3ff0000109ab922e"); //  5233267
    addPowData("3ff00842204a3715", POW_B1, "3ff000010e55fa30"); //  4235090
    addPowData("3ff0088ee1bcbc5c", POW_B1, "3ff000011823f4e8"); //  6182890
    addPowData("3ff008feca56dfb2", POW_B1, "3ff00001266f2416"); //  4450657
    addPowData("3ff009153b12f7fb", POW_B1, "3ff00001294cd942"); //  6967668
    addPowData("3ff0091653a67e2a", POW_B1, "3ff000012970aed8"); //  4152764
    addPowData("3ff0091e30077029", POW_B1, "3ff000012a71b252"); //  4754632
    addPowData("3ff0091e8cbf5d10", POW_B1, "3ff000012a7d89c6"); //  7567978
    addPowData("3ff00939e8deaf04", POW_B1, "3ff000012dfc1048"); //  4856290
    addPowData("3ff0094792ff27e8", POW_B1, "3ff000012fbad0ac"); //  4297357
    addPowData("3ff009645e4bb389", POW_B1, "3ff0000133683264"); //  4079280
    addPowData("3ff009b0b2616930", POW_B1, "3ff000013d27849e"); //  4251796
    addPowData("3ff009b9e70187a2", POW_B1, "3ff000013e547404"); //  4658336
    addPowData("3ff009de0cb6ef67", POW_B1, "3ff0000142f21a62"); //  4554792
    addPowData("3ff009ea31bdbe75", POW_B1, "3ff00001447f19aa"); //  5826741
    addPowData("3ff009f707e9a97b", POW_B1, "3ff000014622b662"); //  8223380
    addPowData("3ff009fb36d3c998", POW_B1, "3ff0000146ab74ec"); //  5535197
    addPowData("3ff00a28820b8780", POW_B1, "3ff000014c7401ae"); //  7574436
    addPowData("3ff00a3632db72be", POW_B1, "3ff000014e3382a6"); //  4766695
    addPowData("3ff00a37999dc7cf", POW_B1, "3ff000014e61513c"); //  4824701
    addPowData("3ff00aa7c8696175", POW_B1, "3ff000015cb3fc44"); //  5746783
    addPowData("3ff00abd92d5068e", POW_B1, "3ff000015f7c2a02"); //  4602521
    addPowData("3ff00aed7ecfd407", POW_B1, "3ff00001659a5334"); //  4073645
    addPowData("3ff00b1fa2b4a6a9", POW_B1, "3ff000016c00e92e"); //  6719995
    addPowData("3ff00b2d0eb64e9f", POW_B1, "3ff000016db786be"); //  5761561
    addPowData("3ff00b69e36600ef", POW_B1, "3ff00001757b59f8"); //  4604021
    addPowData("3ff00bef8115b65d", POW_B1, "3ff0000186893de0"); //  4225778
    addPowData("3ff00bf4c7765387", POW_B1, "3ff000018735966e"); //  4106083
    addPowData("3ff00bfe8ddc73ff", POW_B1, "3ff000018874f61e"); //  5553050
    addPowData("3ff00c15de2b0d5e", POW_B1, "3ff000018b6eaab6"); //  5400886
    addPowData("3ff00c7dd4479905", POW_B1, "3ff0000198b316ac"); //  4139107
    addPowData("3ff00d276f3b5ce5", POW_B1, "3ff00001ae576102"); //  4587944
    addPowData("3ff00d27ce901c9a", POW_B1, "3ff00001ae638ad2"); //  5489330
    addPowData("3ff00d67c2b33446", POW_B1, "3ff00001b68c6810"); //  4609142
    addPowData("3ff00dafadce5c27", POW_B1, "3ff00001bfb9439e"); //  4821490
    addPowData("3ff00dcf80fe76bb", POW_B1, "3ff00001c3c89e06"); //  6837278
    addPowData("3ff00e00380e10d7", POW_B1, "3ff00001c9ff83c8"); //  5380897
    addPowData("3ff00e5ccd725b39", POW_B1, "3ff00001d5ced49a"); //  6807386
    addPowData("3ff00e86a21944c7", POW_B1, "3ff00001db24b940"); //  8891943
    addPowData("3ff00e86a7859088", POW_B1, "3ff00001db256a52"); //  4924111
    addPowData("3ff00eb271b30fab", POW_B1, "3ff00001e0bb385a"); //  5135681
    addPowData("3ff00f01ca36b0b7", POW_B1, "3ff00001ead9d548"); //  4524583
    addPowData("3ff00f3fdf937c0f", POW_B1, "3ff00001f2c4b382"); //  4786833
    addPowData("3ff00f83f7bdfa67", POW_B1, "3ff00001fb73ac9e"); //  4943465
    addPowData("3ff0103649af599a", POW_B1, "3ff00002123055bc"); //  4827898
    addPowData("3ff010e5e83c7501", POW_B1, "3ff000022893e022"); //  5684826
    addPowData("3ff01146bafecacc", POW_B1, "3ff0000234eb6822"); //  5889950
    addPowData("3ff011b575f20dec", POW_B1, "3ff00002430864f4"); //  5010701
    addPowData("3ff011c0fef22410", POW_B1, "3ff000024480bffe"); //  4725245
    addPowData("3ff011cd94e46714", POW_B1, "3ff00002461b60b0"); //  4987380
    addPowData("3ff0123e52985644", POW_B1, "3ff0000254797fd0"); //  4367125
    addPowData("3ff0124f4152a403", POW_B1, "3ff0000256a1e184"); //  4706847
    addPowData("3ff0126d052860e2", POW_B1, "3ff000025a6cde26"); //  4402197
    addPowData("3ff01270a65c85d5", POW_B1, "3ff000025ae345f4"); //  4457488
    addPowData("3ff012c4fd4385cd", POW_B1, "3ff0000265a26b38"); //  4873118
    addPowData("3ff012d826f868c8", POW_B1, "3ff0000268137b10"); //  4511356
    addPowData("3ff0131d07497794", POW_B1, "3ff0000270da035c"); //  5449036
    addPowData("3ff01340687303d7", POW_B1, "3ff00002755bedb6"); //  3976336
    addPowData("3ff01349f3ac164b", POW_B1, "3ff000027693328a"); //  4916843
    addPowData("3ff0142094f13c33", POW_B1, "3ff0000291ea8804"); //  4089245
    addPowData("3ff014797ba4c0ef", POW_B1, "3ff000029d3d4906"); //  4236006
    addPowData("3ff014b591802818", POW_B1, "3ff00002a4e451b6"); //  6881803
    addPowData("3ff014cf1b9413aa", POW_B1, "3ff00002a824f9aa"); //  4230473
    addPowData("3ff014da477e1774", POW_B1, "3ff00002a9913162"); //  4485366
    addPowData("3ff0150325b205a6", POW_B1, "3ff00002aec58ca0"); //  4200014
    addPowData("3ff01545322f876f", POW_B1, "3ff00002b72eba66"); //  4154799
    addPowData("3ff015cba20ec276", POW_B1, "3ff00002c84cef0e"); //  4518035
    addPowData("3ff016571b207ee8", POW_B1, "3ff00002da0eb434"); //  4830526
    addPowData("3ff016d3dfdc8cc0", POW_B1, "3ff00002e9f0b5ea"); //  4708136
    addPowData("3ff01743c6cc53ca", POW_B1, "3ff00002f82f0348"); //  4435558
    addPowData("3ff0177da6425b0e", POW_B1, "3ff00002ff8ca296"); //  4211738
    addPowData("3ff01789d91cbe83", POW_B1, "3ff00003011a146c"); //  4256760
    addPowData("3ff017ac17ac002d", POW_B1, "3ff000030575c5da"); //  7205257
    addPowData("3ff0189be8b70297", POW_B1, "3ff0000323fa0fe8"); //  6769143
    addPowData("3ff018abdf39553b", POW_B1, "3ff0000326020250"); //  4090203
    addPowData("3ff0192278704be3", POW_B1, "3ff000033518c576"); //  4137160
    addPowData("3ff019be4095d6ae", POW_B1, "3ff0000348e9f02a"); //  4260583
    addPowData("3ff019ce7bda4503", POW_B1, "3ff000034afa7cee"); //  4985682
    addPowData("3ff01a4586c04fe7", POW_B1, "3ff000035a1ea55a"); //  5519790
    addPowData("3ff01b15d687a1cf", POW_B1, "3ff00003749c73ac"); //  5184517
    addPowData("3ff01bec8f058641", POW_B1, "3ff000038fe982c2"); //  5902350
    addPowData("3ff01c1791d40d0a", POW_B1, "3ff00003956153b8"); //  4497250
    addPowData("3ff01c1d9e395eba", POW_B1, "3ff0000396262bf8"); //  5163245
    addPowData("3ff01d11a2a555de", POW_B1, "3ff00003b52aba6c"); //  7361881
    addPowData("3ff01d1c6f0356e4", POW_B1, "3ff00003b68a12bc"); //  4513763
    addPowData("3ff01d2883db0d7b", POW_B1, "3ff00003b8132994"); //  4084892
    addPowData("3ff01e71e773ffc2", POW_B1, "3ff00003e1eea894"); //  4556510
    addPowData("3ff01f434d3c5e64", POW_B1, "3ff00003fc88ecc0"); //  4465484
    addPowData("3ff01f6112a7fe03", POW_B1, "3ff0000400511134"); //  4643283
    addPowData("3ff020fb74e9f170", POW_B1, "3ff00004346fbfa2"); //  5133963
    addPowData("3ff021a0782fbc23", POW_B1, "3ff0000449634c62"); //  4890574
    addPowData("3ff0232e074df506", POW_B1, "3ff000047bda0f6c"); //  5500565
    addPowData("3ff02414e9f5c03b", POW_B1, "3ff0000499267fd0"); //  4511476
    addPowData("3ff02430709f51ec", POW_B1, "3ff000049ca49698"); //  4560402
    addPowData("3ff024c8eb334fe8", POW_B1, "3ff00004affcebd4"); //  6553530
    addPowData("3ff02505e61f7b8d", POW_B1, "3ff00004b7b94920"); //  4047785
    addPowData("3ff025cadab76a1f", POW_B1, "3ff00004d0b4be2e"); //  4853528
    addPowData("3ff025e4a878d29f", POW_B1, "3ff00004d3fa8b08"); //  4455322
    addPowData("3ff0262e9bbe0441", POW_B1, "3ff00004dd5b75f0"); //  4453408
    addPowData("3ff0270f120aff91", POW_B1, "3ff00004f9d1f76e"); //  4976675
    addPowData("3ff02754e840e5b2", POW_B1, "3ff0000502acb29a"); //  4128831
    addPowData("3ff02c36701515db", POW_B1, "3ff00005a1002d44"); //  4861728
    addPowData("3ff032d08c7e2e21", POW_B1, "3ff0000676db002c"); //  5441084
    addPowData("3ff035a55ff1c78a", POW_B1, "3ff00006d27732bc"); //  4602342
    addPowData("3ff03644918d0785", POW_B1, "3ff00006e693debe"); //  4886676
    addPowData("3ff037bb1dedc8cd", POW_B1, "3ff0000715e28914"); //  4568525
    addPowData("3ff0395604cd3567", POW_B1, "3ff0000749c3c032"); //  4687998
    addPowData("3ff039bf04e42f96", POW_B1, "3ff000075704c2c6"); //  4208189
    addPowData("3ff03e7bdeeecc32", POW_B1, "3ff00007f004ed7a"); //  4685971
    addPowData("3ff03ed24e556ded", POW_B1, "3ff00007faea95ee"); //  4653679
    addPowData("3ff03ef1c681f0bd", POW_B1, "3ff00007fee23120"); //  5468496
    addPowData("3ff03fcb5661fa8f", POW_B1, "3ff000081a4eb43e"); //  4989655
    addPowData("3ff042d910585bee", POW_B1, "3ff000087ccc6c26"); //  4645002
    addPowData("3ff0450fda5471a3", POW_B1, "3ff00008c42a03da"); //  4947881
    addPowData("3ff04641fc8b11ca", POW_B1, "3ff00008eab1bc98"); //  5249567
    addPowData("3ff047a41ad06417", POW_B1, "3ff0000917400204"); //  6893082
    addPowData("3ff04db90c5c127d", POW_B1, "3ff00009daf8d8ee"); //  4082950
    addPowData("3ff04f9e73a25ac8", POW_B1, "3ff0000a17eef48e"); //  4656494
    addPowData("3ff04fcbf8abc9fd", POW_B1, "3ff0000a1da61602"); //  5064579
    addPowData("3ff0511862bf450d", POW_B1, "3ff0000a4760eee0"); //  4546728
    addPowData("3ff051210e0bfeb6", POW_B1, "3ff0000a48777d74"); //  4462397
    addPowData("3ff055982b0f8b04", POW_B1, "3ff0000ad7de456c"); //  4691819
    addPowData("3ff05727a94317f9", POW_B1, "3ff0000b09f2a4ac"); //  4662098
    addPowData("3ff05e9f2de8eae8", POW_B1, "3ff0000bf9504088"); //  4467661
    addPowData("3ff066a0bcc57b6b", POW_B1, "3ff0000cf97de4b2"); //  6835865
    addPowData("3ff068689beee8b2", POW_B1, "3ff0000d3267db9a"); //  6279232
    addPowData("3ff06c311b09de1e", POW_B1, "3ff0000dab3d2c1e"); //  4936538
    addPowData("3ff06fbdc15a990b", POW_B1, "3ff0000e1c81b8d0"); //  5327644
    addPowData("3ff07003413b4e40", POW_B1, "3ff0000e252a9014"); //  4842005
    addPowData("3ff0707b97ed6426", POW_B1, "3ff0000e3428b132"); //  4927198
    addPowData("3ff077df0910b501", POW_B1, "3ff0000f1f96de9e"); //  4200571,4201109
    addPowData("3ff07f468476cd37", POW_B1, "3ff000100b1bf6fa"); //  4600403
    addPowData("3ff08da4fce37b7d", POW_B1, "3ff00011d2fefcd4"); //  4947399
    addPowData("3ff08ee23b88ee24", POW_B1, "3ff00011fa3db176"); //  4641441,4936097
    addPowData("3ff0907c57df2523", POW_B1, "3ff000122cf4fcd0"); //  5965639
    addPowData("3ff09d1632ec4b9a", POW_B1, "3ff00013bb369796"); //  4127635
    addPowData("3ff09e92f87b35af", POW_B1, "3ff00013ea2501a4"); //  4220788
    addPowData("3ff0a900bef42c6d", POW_B1, "3ff0001532be2c26"); //  5913304
    addPowData("3ff0b33bfdb9a6c8", POW_B1, "3ff000167457a5ce"); //  5603126
    addPowData("3ff0b385e0945b49", POW_B1, "3ff000167d6737f2"); //  4194413
    addPowData("3ff0c1dd413c5db1", POW_B1, "3ff000183edd3e2a"); //  5966796
    addPowData("3ff0c68383edcd8b", POW_B1, "3ff00018d041d5b4"); //  6284987
    addPowData("3ff0d0d3019dddb8", POW_B1, "3ff0001a121dc50e"); //  5638083
    addPowData("3ff0dd223ceffaf9", POW_B1, "3ff0001b915e94ac"); //  6284877
    addPowData("3ff0ef73f1a9aa75", POW_B1, "3ff0001dc9b6eb1a"); //  5971614
    addPowData("3ff15e3c3258a73d", POW_B1, "3ff0002b045ced4c"); //  4944279
    addPowData("3ff29c4ea7efe276", POW_B1, "3ff0004f3e9a3c94"); //  5901858
    addPowData("3ff2e90398bec6e7", POW_B1, "3ff000579e8208fa"); //  6080877
    addPowData("3ff318ed7b598d20", POW_B1, "3ff0005cc8807672"); //  5981639
    addPowData("3ff348ec880ef24d", POW_B1, "3ff00061e7db55c4"); //  4941249
    addPowData("3ff37391b11ff076", POW_B1, "3ff000666a5c8088"); //  6021186
    addPowData("3ff515fde2e99b0d", POW_B1, "3ff00090b8cf57de"); //  5968978
    addPowData("3ff6a89b5ffae2dd", POW_B1, "3ff000b67158530a"); //  7638637
    addPowData("3ff99efec0fbc5d8", POW_B1, "3ff000f6e0b478fe"); //  6806564
    addPowData("3ffffcf9acb020be", POW_B1, "3ff0016b472e0602"); //  5640221
    */
    addPowData("3ff0192278704be3", POW_B1, "3ff000033518c576"); //  4137160
    addPowData("3ff000002fc6a33f", POW_B1, "3ff0000000061d86"); //  4065476
    addPowData("3ff00314b1e73ecf", POW_B1, "3ff0000064ea3ef8"); //  4071538
    addPowData("3ff0068cd52978ae", POW_B1, "3ff00000d676966c"); //  4109544
    addPowData("3ff0032fda05447d", POW_B1, "3ff0000068636fe0"); //  4123826
    addPowData("3ff00051c09cc796", POW_B1, "3ff000000a76c20e"); //  4166806
    addPowData("3ff00bef8115b65d", POW_B1, "3ff0000186893de0"); //  4225778
    addPowData("3ff009b0b2616930", POW_B1, "3ff000013d27849e"); //  4251796
    addPowData("3ff00364ba163146", POW_B1, "3ff000006f26a9dc"); //  4257157
    addPowData("3ff019be4095d6ae", POW_B1, "3ff0000348e9f02a"); //  4260583
    addPowData("3ff0123e52985644", POW_B1, "3ff0000254797fd0"); //  4367125
    addPowData("3ff0126d052860e2", POW_B1, "3ff000025a6cde26"); //  4402197
  }

  private static void addPowData(String a, String b, String ret) {
    powData.put(new PowData(hexToDouble(a), hexToDouble(b)), hexToDouble(ret));
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

  /**
   *  *** methods are same as {@link java.lang.Math} methods, guaranteed by the call start ***
   */

  /**
   * finally calls {@link java.lang.Math#addExact(long, long)}
   */

  public static long addExact(long x, long y) {
    return StrictMath.addExact(x, y);
  }

  /**
   * finally calls {@link java.lang.Math#addExact(int, int)}
   */

  public static int addExact(int x, int y) {
    return StrictMath.addExact(x, y);
  }

  /**
   * finally calls {@link java.lang.Math#subtractExact(long, long)}
   */

  public static long subtractExact(long x, long y) {
    return StrictMath.subtractExact(x, y);
  }

  /**
   * finally calls {@link java.lang.Math#floorMod(long, long)}
   */
  public static long multiplyExact(long x, long y) {
    return StrictMath.multiplyExact(x, y);
  }

  public static long multiplyExact(long x, int y) {
    return multiplyExact(x, (long) y);
  }

  public static int multiplyExact(int x, int y) {
    return StrictMath.multiplyExact(x, y);
  }

  /**
   * finally calls {@link java.lang.Math#floorDiv(long, long)}
   */
  public static long floorDiv(long x, long y) {
    return StrictMath.floorDiv(x, y);
  }

  public static long floorDiv(long x, int y) {
    return floorDiv(x, (long) y);
  }

  /**
   * finally calls {@link java.lang.Math#min(int, int)}
   */
  public static int min(int a, int b) {
    return StrictMath.min(a, b);
  }

  /**
   * finally calls {@link java.lang.Math#min(long, long)}
   */
  public static long min(long a, long b) {
    return StrictMath.min(a, b);
  }

  /**
   * finally calls {@link java.lang.Math#max(int, int)}
   */
  public static int max(int a, int b) {
    return StrictMath.max(a, b);
  }

  /**
   * finally calls {@link java.lang.Math#max(long, long)}
   */
  public static long max(long a, long b) {
    return StrictMath.max(a, b);
  }

  /**
   * finally calls {@link java.lang.Math#round(float)}
   */
  public static int round(float a) {
    return StrictMath.round(a);
  }

  /**
   * finally calls {@link java.lang.Math#round(double)}
   */
  public static long round(double a) {
    return StrictMath.round(a);
  }

  /**
   * finally calls {@link java.lang.Math#signum(double)}
   */
  public static double signum(double d) {
    return StrictMath.signum(d);
  }

  /**
   * finally calls {@link java.lang.Math#signum(float)}
   */
  public static long abs(long a) {
    return StrictMath.abs(a);
  }

  /**
   *  *** methods are same as {@link java.lang.Math} methods, guaranteed by the call end ***
   */

  /**
   *  *** methods are same as {@link java.lang.Math} methods by mathematical  algorithms***
   * /


   /**
   * mathematical integer: ceil(i) = floor(i) = i
   * @return the smallest (closest to negative infinity) double value that is greater
   * than or equal to the argument and is equal to a mathematical integer.
   * Note: you should call {@link #ceilAsInt(double)} or {@link #ceilAsLong(double)}
   * instead of this method.
   */
  public static double ceil(double a) {
    return StrictMath.ceil(a);
  }

  public static long ceilAsLong(double a) {
    return (long) ceil(a);
  }

  public static int ceilAsInt(double a) {
    return (int) ceil(a);
  }


  /**
   * *** methods are no matters  ***
   */
  public static double random() {
    return StrictMath.random();
  }

}
