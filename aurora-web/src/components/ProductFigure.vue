<script setup lang="ts">
import { computed } from 'vue'

import { monogram, productKind } from '@/utils/catalog'

const props = defineProps<{
  title: string
  label?: string
}>()

const kind = computed(() => productKind(props.title))
const mark = computed(() => monogram(props.title))
const description = computed(() => props.label ?? `${props.title} 的目录图形`)
</script>

<template>
  <svg
    class="product-figure"
    viewBox="0 0 320 240"
    role="img"
    :aria-label="description"
    xmlns="http://www.w3.org/2000/svg"
  >
    <title>{{ description }}</title>
    <ellipse cx="160" cy="208" rx="92" ry="10" fill="var(--stone-2)" />

    <g v-if="kind === 'camera'">
      <rect x="66" y="84" width="188" height="112" rx="10" fill="var(--ink)" />
      <rect x="96" y="66" width="62" height="22" rx="4" fill="var(--ink)" />
      <rect x="214" y="98" width="22" height="10" rx="2" fill="var(--signal)" />
      <circle cx="160" cy="140" r="46" fill="var(--paper)" />
      <circle cx="160" cy="140" r="31" fill="var(--signal)" />
      <circle cx="160" cy="140" r="17" fill="var(--ink)" />
      <rect x="76" y="100" width="30" height="8" rx="2" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'headphones'">
      <path d="M82 150 V118 C82 74 240 74 240 118 V150" fill="none" stroke="var(--ink)" stroke-width="18" />
      <rect x="62" y="132" width="44" height="68" rx="18" fill="var(--ink)" />
      <rect x="216" y="132" width="44" height="68" rx="18" fill="var(--ink)" />
      <rect x="74" y="146" width="20" height="40" rx="9" fill="var(--signal)" />
      <rect x="228" y="146" width="20" height="40" rx="9" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'keyboard'">
      <rect x="42" y="96" width="236" height="98" rx="6" fill="var(--ink)" />
      <g fill="var(--paper)">
        <rect v-for="i in 13" :key="'a' + i" :x="54 + (i - 1) * 17" y="108" width="13" height="13" rx="1" />
        <rect v-for="i in 12" :key="'b' + i" :x="61 + (i - 1) * 17" y="128" width="13" height="13" rx="1" />
        <rect v-for="i in 11" :key="'c' + i" :x="68 + (i - 1) * 17" y="148" width="13" height="13" rx="1" />
      </g>
      <rect x="104" y="168" width="112" height="13" rx="2" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'mouse'">
      <path
        d="M160 72 C196 72 214 102 214 142 C214 182 196 202 160 202 C124 202 106 182 106 142 C106 102 124 72 160 72 Z"
        fill="var(--ink)"
      />
      <path d="M160 76 V118" stroke="var(--paper)" stroke-width="3" />
      <rect x="154" y="92" width="12" height="30" rx="6" fill="var(--signal)" />
      <circle cx="160" cy="176" r="4" fill="var(--paper)" />
    </g>

    <g v-else-if="kind === 'chair'">
      <path d="M112 62 C112 50 122 42 134 42 H186 C198 42 208 50 208 62 V126 H112 Z" fill="var(--ink)" />
      <rect x="100" y="126" width="120" height="26" rx="6" fill="var(--signal)" />
      <rect x="112" y="152" width="14" height="48" fill="var(--ink)" />
      <rect x="194" y="152" width="14" height="48" fill="var(--ink)" />
      <rect x="92" y="198" width="136" height="8" rx="3" fill="var(--ink)" />
      <rect x="152" y="152" width="16" height="50" fill="var(--ink)" />
    </g>

    <g v-else-if="kind === 'coffee-machine'">
      <rect x="92" y="58" width="136" height="142" rx="6" fill="var(--ink)" />
      <rect x="112" y="78" width="96" height="42" rx="3" fill="var(--paper)" />
      <rect x="122" y="88" width="42" height="8" rx="2" fill="var(--signal)" />
      <rect x="122" y="102" width="60" height="6" rx="2" fill="var(--stone-2)" />
      <rect x="146" y="132" width="28" height="8" fill="var(--stone-2)" />
      <path d="M132 158 H188 V190 H132 Z" fill="var(--paper)" />
      <rect x="188" y="164" width="10" height="18" rx="5" fill="none" stroke="var(--paper)" stroke-width="4" />
      <circle cx="208" cy="150" r="6" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'vacuum'">
      <rect x="142" y="40" width="18" height="118" rx="8" fill="var(--ink)" transform="rotate(12 151 99)" />
      <rect x="116" y="146" width="88" height="46" rx="14" fill="var(--ink)" />
      <circle cx="160" cy="168" r="14" fill="var(--signal)" />
      <rect x="104" y="194" width="112" height="14" rx="6" fill="var(--ink)" />
      <rect x="146" y="36" width="30" height="12" rx="5" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'tablet'">
      <rect x="82" y="56" width="156" height="138" rx="10" fill="var(--ink)" />
      <rect x="94" y="68" width="132" height="110" rx="3" fill="var(--paper)" />
      <rect x="106" y="82" width="70" height="10" rx="2" fill="var(--ink)" />
      <rect x="106" y="100" width="104" height="6" rx="2" fill="var(--stone-2)" />
      <rect x="106" y="112" width="88" height="6" rx="2" fill="var(--stone-2)" />
      <rect x="106" y="130" width="46" height="32" rx="2" fill="var(--signal)" />
      <rect x="160" y="130" width="50" height="32" rx="2" fill="var(--stone-2)" />
    </g>

    <g v-else-if="kind === 'phone'">
      <rect x="116" y="38" width="88" height="164" rx="14" fill="var(--ink)" />
      <rect x="124" y="48" width="72" height="144" rx="8" fill="var(--paper)" />
      <rect x="134" y="58" width="32" height="32" rx="7" fill="var(--signal)" />
      <circle cx="146" cy="70" r="6" fill="var(--ink)" />
      <circle cx="158" cy="78" r="4" fill="var(--paper)" />
      <rect x="134" y="102" width="52" height="7" rx="2" fill="var(--ink)" />
      <rect x="134" y="116" width="40" height="6" rx="2" fill="var(--stone-2)" />
      <rect x="134" y="136" width="52" height="30" rx="3" fill="var(--stone-2)" />
      <rect x="148" y="178" width="24" height="4" rx="2" fill="var(--ink)" />
    </g>

    <g v-else-if="kind === 'battery'">
      <rect x="92" y="72" width="112" height="132" rx="10" fill="var(--ink)" />
      <rect x="110" y="92" width="76" height="92" rx="4" fill="var(--paper)" />
      <rect x="122" y="112" width="52" height="6" rx="2" fill="var(--signal)" />
      <rect x="122" y="126" width="52" height="6" rx="2" fill="var(--signal)" />
      <rect x="122" y="140" width="34" height="6" rx="2" fill="var(--signal)" />
      <rect x="204" y="102" width="20" height="10" rx="4" fill="var(--stone-2)" />
      <path d="M224 107 C254 107 254 160 224 160" fill="none" stroke="var(--ink)" stroke-width="5" />
    </g>

    <g v-else-if="kind === 'dock'">
      <rect x="72" y="126" width="176" height="46" rx="8" fill="var(--ink)" />
      <rect x="90" y="140" width="18" height="9" rx="2" fill="var(--paper)" />
      <rect x="118" y="140" width="18" height="9" rx="2" fill="var(--paper)" />
      <rect x="146" y="140" width="28" height="9" rx="2" fill="var(--signal)" />
      <rect x="184" y="140" width="28" height="9" rx="2" fill="var(--paper)" />
      <path d="M248 150 C272 150 272 94 250 94 H218" fill="none" stroke="var(--ink)" stroke-width="5" />
      <rect x="200" y="86" width="20" height="14" rx="2" fill="var(--ink)" />
    </g>

    <g v-else-if="kind === 'speaker'">
      <rect x="104" y="54" width="112" height="150" rx="8" fill="var(--ink)" />
      <circle cx="160" cy="102" r="28" fill="var(--stone-2)" />
      <circle cx="160" cy="102" r="15" fill="var(--signal)" />
      <circle cx="160" cy="164" r="22" fill="var(--stone-2)" />
      <circle cx="160" cy="164" r="10" fill="var(--ink)" />
      <rect x="144" y="190" width="32" height="6" rx="2" fill="var(--signal)" />
    </g>

    <g v-else-if="kind === 'purifier'">
      <rect x="112" y="42" width="96" height="166" rx="10" fill="var(--ink)" />
      <rect x="124" y="58" width="72" height="84" rx="4" fill="var(--paper)" />
      <g fill="var(--stone-2)">
        <rect x="134" y="70" width="52" height="4" rx="2" />
        <rect x="134" y="82" width="52" height="4" rx="2" />
        <rect x="134" y="94" width="52" height="4" rx="2" />
        <rect x="134" y="106" width="52" height="4" rx="2" />
        <rect x="134" y="118" width="52" height="4" rx="2" />
      </g>
      <circle cx="160" cy="164" r="12" fill="var(--signal)" />
      <rect x="138" y="188" width="44" height="6" rx="3" fill="var(--stone-2)" />
    </g>

    <g v-else-if="kind === 'coffee'">
      <path d="M116 108 H204 L194 190 H126 Z" fill="var(--ink)" />
      <path d="M204 122 H224 C236 122 236 158 224 158 H198" fill="none" stroke="var(--ink)" stroke-width="8" />
      <rect x="128" y="130" width="64" height="34" rx="3" fill="var(--signal)" />
      <path d="M146 92 C146 78 154 74 154 62" fill="none" stroke="var(--stone-2)" stroke-width="5" stroke-linecap="round" />
      <path d="M168 92 C168 78 176 74 176 62" fill="none" stroke="var(--stone-2)" stroke-width="5" stroke-linecap="round" />
      <rect x="130" y="196" width="60" height="6" rx="3" fill="var(--ink)" />
    </g>

    <g v-else-if="kind === 'charger'">
      <rect x="104" y="86" width="112" height="96" rx="12" fill="var(--ink)" />
      <rect x="134" y="66" width="12" height="24" rx="3" fill="var(--stone-2)" />
      <rect x="174" y="66" width="12" height="24" rx="3" fill="var(--stone-2)" />
      <rect x="126" y="110" width="68" height="8" rx="2" fill="var(--signal)" />
      <rect x="126" y="126" width="42" height="6" rx="2" fill="var(--paper)" />
      <path d="M160 142 V188 C160 200 178 200 178 188" fill="none" stroke="var(--ink)" stroke-width="5" />
    </g>

    <g v-else>
      <rect x="78" y="64" width="164" height="136" fill="none" stroke="var(--ink)" stroke-width="2" />
      <text
        x="160"
        y="152"
        text-anchor="middle"
        fill="var(--ink)"
        font-family="var(--font-display)"
        font-size="76"
        font-weight="800"
      >
        {{ mark }}
      </text>
      <rect x="118" y="172" width="84" height="4" fill="var(--signal)" />
    </g>
  </svg>
</template>

<style scoped>
.product-figure {
  width: 100%;
  height: auto;
}
</style>
