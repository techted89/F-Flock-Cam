class PriorityGate {
  constructor({ premiumConcurrency = 8, freeConcurrency = 3 } = {}) {
    this.premiumConcurrency = premiumConcurrency;
    this.freeConcurrency = freeConcurrency;
    this.activePremium = 0;
    this.activeFree = 0;
  }

  async acquire(premium) {
    const started = Date.now();
    while (true) {
      if (premium && this.activePremium < this.premiumConcurrency) {
        this.activePremium += 1;
        return;
      }
      // Free work yields whenever premium work is active. A timeout prevents
      // permanent starvation if premium traffic is continuous.
      const premiumBusy = this.activePremium > 0;
      if (!premium && !premiumBusy && this.activeFree < this.freeConcurrency) {
        this.activeFree += 1;
        return;
      }
      if (!premium && Date.now() - started > 2500) {
        this.activeFree += 1;
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  }

  release(premium) {
    if (premium) {
      this.activePremium = Math.max(0, this.activePremium - 1);
    } else {
      this.activeFree = Math.max(0, this.activeFree - 1);
    }
  }

  async run(tier, task) {
    const premium = tier === 'premium';
    await this.acquire(premium);
    try {
      return await task();
    } finally {
      this.release(premium);
    }
  }
}

const priorityGate = new PriorityGate({
  premiumConcurrency: Number(process.env.PREMIUM_CONCURRENCY || 8),
  freeConcurrency: Number(process.env.FREE_CONCURRENCY || 3),
});

module.exports = { PriorityGate, priorityGate };
