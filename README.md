# Spark Native Engine Fuzz Testing

This is a fuzz testing framework for the Spark Native Engine. It is mostly copied from [datafusion-comet/fuzz-testing](https://github.com/apache/datafusion-comet/tree/main/fuzz-testing).

I use it to test the popular spark native engines:
- [Datafusion Comet](https://github.com/apache/datafusion-comet)
- [Blaze](https://github.com/kwai/blaze/)
- [Gluten](https://github.com/apache/incubator-gluten)

There is a scheduled [Spark Native Engine Fuzz Testing GHA](https://github.com/wForget/fuzz-test-spark-native/actions/workflows/master.yml) that runs fuzz tests after building for each native engine, and reports the test results in issues.

You can see the fuzz test reports of each native engine in [issues list](https://github.com/apache/incubator-gluten/issues), which shows the running failures and consistency issues in spark with native engine.

**Note**: The report may be truncated due to issue comment length limit. You can download the complete report file in the action's artifacts.

## Bugs found by fuzz testing

- [\[GLUTEN-8499\]\[VL\] Result mismatch of try_cast](https://github.com/apache/incubator-gluten/issues/8499)
- [\[BLAZE-760\]: BinaryType is not supported in RangePartitioning](https://github.com/kwai/blaze/issues/760)
